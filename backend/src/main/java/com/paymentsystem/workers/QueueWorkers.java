package com.paymentsystem.workers;

import com.paymentsystem.bank.BankRequest;
import com.paymentsystem.bank.BankResponse;
import com.paymentsystem.bank.SimulatedBank;
import com.paymentsystem.config.BankSimulatorProperties;
import com.paymentsystem.config.ProcessingProperties;
import com.paymentsystem.queue.PaymentQueue;
import com.paymentsystem.queue.PaymentRequestMessage;
import com.paymentsystem.queue.Queues;
import com.paymentsystem.services.PaymentProcessor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Runs the queue consumers. Each worker is a named consumer in a Redis consumer group, so Redis records which
// worker holds each message. A message is acknowledged only after it was handled; otherwise it stays pending
// and another worker takes it over once it has been idle for reclaim-idle-timeout.
@Slf4j
@Component
public class QueueWorkers implements SmartLifecycle {

    private record Lane(String stream, String group, MessageHandler handler) {
    }

    private final StringRedisTemplate redis;
    private final PaymentQueue paymentQueue;
    private final PaymentProcessor processor;
    private final ProcessingProperties properties;
    private final BankSimulatorProperties bankProperties;
    private final List<Lane> lanes;
    private final String instanceId = "api-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public QueueWorkers(
            StringRedisTemplate redis,
            PaymentQueue paymentQueue,
            PaymentProcessor processor,
            SimulatedBank bank,
            ProcessingProperties properties,
            BankSimulatorProperties bankProperties) {
        this.redis = redis;
        this.paymentQueue = paymentQueue;
        this.processor = processor;
        this.properties = properties;
        this.bankProperties = bankProperties;
        this.lanes = List.of(
                new Lane(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS,
                        fields -> processor.processPaymentRequest(PaymentRequestMessage.fromFields(fields))),
                new Lane(Queues.BANK_RESPONSES, Queues.PAYMENT_WORKERS,
                        fields -> processor.processBankResponse(BankResponse.fromFields(fields))),
                new Lane(Queues.BANK_REQUESTS, Queues.BANK_SIMULATOR,
                        fields -> bank.handle(BankRequest.fromFields(fields))));
    }

    @Override
    public void start() {
        running = true;
        if (!properties.workersEnabled()) {
            return;
        }
        createGroups();
        for (Lane lane : activeLanes()) {
            for (int i = 1; i <= properties.workerCount(); i++) {
                String consumer = instanceId + "-" + lane.stream().replace(':', '-') + "-" + i;
                threads.add(Thread.ofVirtual().name(consumer).start(() -> pollLoop(lane, consumer)));
            }
        }
        threads.add(Thread.ofVirtual().name(instanceId + "-maintenance").start(this::maintenanceLoop));
        log.info("Started {} queue worker threads as {}", threads.size(), instanceId);
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void createGroups() {
        for (Lane lane : lanes) {
            try {
                redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                        lane.stream().getBytes(StandardCharsets.UTF_8), lane.group(), ReadOffset.from("0-0"), true));
            } catch (RuntimeException ex) {
                if (!String.valueOf(ex.getMessage()).contains("BUSYGROUP")
                        && !String.valueOf(ex.getCause()).contains("BUSYGROUP")) {
                    throw ex;
                }
            }
        }
    }

    // Reads one batch of new messages for a stream as the named consumer and handles them.
    @SuppressWarnings("unchecked")
    public int pollOnce(String stream, String consumer) {
        Lane lane = lane(stream);
        StreamOffset<String> offset = StreamOffset.create(stream, ReadOffset.lastConsumed());
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(lane.group(), consumer), StreamReadOptions.empty().count(properties.batchSize()), offset);
        int handled = 0;
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) {
                if (handle(lane, record.getId(), fields(record))) {
                    handled++;
                }
            }
        }
        return handled;
    }

    // Takes over messages that another worker received but never acknowledged, then runs the recovery jobs.
    public void runMaintenance() {
        for (Lane lane : activeLanes()) {
            reclaim(lane);
        }
        int requeued = processor.requeueStaleQueuedAttempts();
        int resent = processor.retryStalePendingAttempts();
        if (requeued > 0 || resent > 0) {
            log.info("Recovery requeued {} queued attempts and resent {} bank requests", requeued, resent);
        }
    }

    private void reclaim(Lane lane) {
        PendingMessages pending = redis.opsForStream().pending(
                lane.stream(), lane.group(), Range.unbounded(), properties.batchSize(), properties.reclaimIdleTimeout());
        String reclaimer = instanceId + "-reclaimer";
        for (PendingMessage message : pending) {
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    lane.stream(), lane.group(), reclaimer, properties.reclaimIdleTimeout(), message.getId());
            for (MapRecord<String, Object, Object> record : claimed) {
                if (message.getTotalDeliveryCount() >= properties.maxDeliveries()) {
                    log.error("Moving message {} on {} to dead letters after {} deliveries",
                            record.getId(), lane.stream(), message.getTotalDeliveryCount());
                    paymentQueue.publishDeadLetter(lane.stream(), fields(record), "max_deliveries_exceeded");
                    paymentQueue.acknowledgeAndDelete(lane.stream(), lane.group(), record.getId());
                } else {
                    handle(lane, record.getId(), fields(record));
                }
            }
        }
    }

    private boolean handle(Lane lane, RecordId id, Map<String, String> fields) {
        try {
            lane.handler().handle(fields);
            paymentQueue.acknowledgeAndDelete(lane.stream(), lane.group(), id);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Message {} on {} failed and stays pending for a retry: {}", id, lane.stream(), ex.toString());
            return false;
        }
    }

    private void pollLoop(Lane lane, String consumer) {
        while (running) {
            try {
                if (pollOnce(lane.stream(), consumer) == 0) {
                    Thread.sleep(properties.idlePollInterval());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.warn("Worker {} could not poll {}: {}", consumer, lane.stream(), ex.toString());
                pause();
            }
        }
    }

    private void maintenanceLoop() {
        while (running) {
            try {
                runMaintenance();
                Thread.sleep(properties.maintenanceInterval());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.warn("Maintenance run failed: {}", ex.toString());
                pause();
            }
        }
    }

    private List<Lane> activeLanes() {
        return lanes.stream()
                .filter(lane -> bankProperties.simulatorEnabled() || !lane.group().equals(Queues.BANK_SIMULATOR))
                .toList();
    }

    private Lane lane(String stream) {
        return lanes.stream()
                .filter(lane -> lane.stream().equals(stream))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown stream " + stream));
    }

    private static Map<String, String> fields(MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new HashMap<>();
        record.getValue().forEach((key, value) -> fields.put(String.valueOf(key), String.valueOf(value)));
        return fields;
    }

    private void pause() {
        try {
            Thread.sleep(properties.maintenanceInterval());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
