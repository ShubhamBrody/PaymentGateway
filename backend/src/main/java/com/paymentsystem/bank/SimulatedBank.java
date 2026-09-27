package com.paymentsystem.bank;

import com.paymentsystem.config.BankSimulatorProperties;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.queue.PaymentQueue;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Stands in for an external bank. It remembers each request by its key, so a retried request gets the same answer.
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulatedBank {

    private static final Duration MEMORY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final PaymentQueue paymentQueue;
    private final BankSimulatorProperties properties;

    public void handle(BankRequest request) {
        String deliveriesKey = "bank:deliveries:" + request.bankKey();
        long deliveryNumber = redis.opsForValue().increment(deliveriesKey);
        redis.expire(deliveriesKey, MEMORY_TTL);

        StoredDecision stored = decisionFor(request);
        BankBehavior.ReplyPlan plan = BankBehavior.plan(
                stored.decision(), deliveryNumber, properties.approveDelay(), properties.slowDelay());
        BankResponse response = new BankResponse(
                request.bankKey(),
                request.attemptId(),
                request.paymentId(),
                stored.decision().outcome(),
                stored.bankReference(),
                stored.decision().declineReason());
        for (int i = 0; i < plan.replies(); i++) {
            replyLater(response, plan.delay());
        }
    }

    private StoredDecision decisionFor(BankRequest request) {
        String key = "bank:decision:" + request.bankKey();
        BankBehavior.Decision fresh = BankBehavior.decide(request.scenario(), ThreadLocalRandom.current());
        String candidate = String.join("|",
                fresh.scenario().name(),
                fresh.outcome().name(),
                newBankReference(),
                fresh.declineReason() == null ? "" : fresh.declineReason());
        redis.opsForValue().setIfAbsent(key, candidate, MEMORY_TTL);
        String[] parts = redis.opsForValue().get(key).split("\\|", -1);
        BankBehavior.Decision decision = new BankBehavior.Decision(
                BankScenario.valueOf(parts[0]), BankOutcome.valueOf(parts[1]), parts[3].isEmpty() ? null : parts[3]);
        return new StoredDecision(decision, parts[2]);
    }

    private void replyLater(BankResponse response, Duration delay) {
        long jitteredMillis = (long) (delay.toMillis() * (0.5 + ThreadLocalRandom.current().nextDouble()));
        Thread.ofVirtual().name("bank-reply").start(() -> {
            try {
                Thread.sleep(jitteredMillis);
                paymentQueue.publishBankResponse(response);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                // A lost reply is realistic; the payment side retries with the same key after its timeout.
                log.warn("Bank reply for {} was lost: {}", response.bankKey(), ex.toString());
            }
        });
    }

    private static String newBankReference() {
        byte[] random = new byte[8];
        ThreadLocalRandom.current().nextBytes(random);
        return "BNK-" + HexFormat.of().withUpperCase().formatHex(random);
    }

    private record StoredDecision(BankBehavior.Decision decision, String bankReference) {
    }
}
