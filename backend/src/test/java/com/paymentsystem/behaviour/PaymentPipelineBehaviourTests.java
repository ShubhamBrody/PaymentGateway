package com.paymentsystem.behaviour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentsystem.bank.BankRequest;
import com.paymentsystem.dtos.CreatePaymentAttemptRequest;
import com.paymentsystem.dtos.CreatePaymentRequest;
import com.paymentsystem.dtos.PaymentRequestStatusResponse;
import com.paymentsystem.dtos.RegisterRequest;
import com.paymentsystem.idempotency.PaymentRequestStatus;
import com.paymentsystem.idempotency.PaymentRequestStatusStore;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.queue.Queues;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import com.paymentsystem.services.AuthService;
import com.paymentsystem.services.PaymentRequestService;
import com.paymentsystem.services.PaymentService;
import com.paymentsystem.support.RedisTestContainerConfig;
import com.paymentsystem.workers.QueueWorkers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

// Behaviour of the queue pipeline against a real Redis. Background workers are off, so each test drives the
// workers step by step and can stop in between, for example to play a worker that crashed mid-message.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:behaviour-${random.uuid};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.processing.workers-enabled=false",
        "app.processing.reclaim-idle-timeout=200ms",
        "app.processing.requeue-after=200ms",
        "app.processing.bank-reply-timeout=300ms",
        "app.processing.max-bank-requests=3",
        "app.processing.max-deliveries=3",
        "app.bank.approve-delay=20ms",
        "app.bank.slow-delay=60ms"
})
@ActiveProfiles("test")
@Import(RedisTestContainerConfig.class)
class PaymentPipelineBehaviourTests {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration PAST_IDLE_TIMEOUT = Duration.ofMillis(350);

    @Autowired
    private AuthService authService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRequestService paymentRequestService;

    @Autowired
    private QueueWorkers queueWorkers;

    @Autowired
    private PaymentRequestStatusStore statusStore;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private ReceiptRepository receiptRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID merchantId;
    private UUID paymentId;

    @BeforeEach
    void freshQueuesAndPayment() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        queueWorkers.createGroups();
        merchantId = authService.register(new RegisterRequest(
                "Behaviour Store", "merchant-" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple"))
                .id();
        paymentId = newPayment();
    }

    @Test
    @DisplayName("A payment request moves through every queue and ends with one receipt")
    void aPaymentRequestFlowsThroughTheQueuesAndIssuesAReceipt() {
        PaymentRequestStatusResponse accepted = submit("request-approve-1", BankScenario.APPROVE).status();
        assertThat(accepted.status()).isEqualTo(PaymentAttemptStatus.QUEUED);
        assertThat(cachedStatus("request-approve-1")).isEqualTo(PaymentAttemptStatus.QUEUED);
        assertThat(streamLength(Queues.PAYMENT_REQUESTS)).isEqualTo(1);

        queueWorkers.pollOnce(Queues.PAYMENT_REQUESTS, "worker-a");
        assertThat(attempt("request-approve-1").getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(cachedStatus("request-approve-1")).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(streamLength(Queues.BANK_REQUESTS)).isEqualTo(1);

        driveUntilFinal("request-approve-1", false);

        PaymentAttempt attempt = attempt("request-approve-1");
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        String receiptNumber = receiptRepository.findByPaymentId(paymentId).orElseThrow().getReceiptNumber();
        PaymentRequestStatus cached = statusStore.find(paymentId, "request-approve-1").orElseThrow();
        assertThat(cached.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(cached.receiptNumber()).isEqualTo(receiptNumber);
        assertThat(streamLength(Queues.PAYMENT_REQUESTS)).isZero();
        assertThat(streamLength(Queues.BANK_RESPONSES)).isZero();
    }

    @Test
    @DisplayName("Retrying with the same idempotency key, even concurrently, creates exactly one attempt")
    void retryingWithTheSameKeyNeverCreatesASecondAttempt() throws Exception {
        int clients = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentRequestService.Submission>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < clients; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return submit("request-same-key", BankScenario.APPROVE);
                }));
            }
            start.countDown();
        }

        Set<UUID> attemptIds = ConcurrentHashMap.newKeySet();
        int firstSubmissions = 0;
        for (Future<PaymentRequestService.Submission> result : results) {
            attemptIds.add(result.get().status().attemptId());
            firstSubmissions += result.get().replayed() ? 0 : 1;
        }
        assertThat(attemptIds).hasSize(1);
        assertThat(firstSubmissions).isEqualTo(1);
        assertThat(attemptRepository.countByPaymentId(paymentId)).isEqualTo(1);
        assertThat(streamLength(Queues.PAYMENT_REQUESTS)).isEqualTo(1);

        driveUntilFinal("request-same-key", false);
        PaymentRequestService.Submission replay = submit("request-same-key", BankScenario.APPROVE);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status().status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(replay.status().source()).isEqualTo("cache");
    }

    @Test
    @DisplayName("A payment request taken by a worker that died is picked up by another worker")
    void aRequestLeftByADeadWorkerIsTakenOverByAnotherWorker() throws Exception {
        submit("request-dead-worker", BankScenario.APPROVE);
        readWithoutHandling(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS, "worker-that-dies");
        assertThat(pendingCount(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS)).isEqualTo(1);

        Thread.sleep(PAST_IDLE_TIMEOUT);
        queueWorkers.runMaintenance();

        assertThat(pendingCount(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS)).isZero();
        assertThat(attempt("request-dead-worker").getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        driveUntilFinal("request-dead-worker", false);
        assertThat(attempt("request-dead-worker").getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("A bank reply received by a worker that died is still applied by another worker")
    void aBankReplyLeftByADeadWorkerIsStillApplied() throws Exception {
        submit("request-dead-listener", BankScenario.APPROVE);
        queueWorkers.pollOnce(Queues.PAYMENT_REQUESTS, "worker-a");
        queueWorkers.pollOnce(Queues.BANK_REQUESTS, "bank-1");
        await().atMost(WAIT).until(() -> streamLength(Queues.BANK_RESPONSES) == 1);
        readWithoutHandling(Queues.BANK_RESPONSES, Queues.PAYMENT_WORKERS, "listener-that-dies");
        assertThat(attempt("request-dead-listener").getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);

        Thread.sleep(PAST_IDLE_TIMEOUT);
        queueWorkers.runMaintenance();

        assertThat(attempt("request-dead-listener").getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(receiptRepository.findByPaymentId(paymentId)).isPresent();
        assertThat(pendingCount(Queues.BANK_RESPONSES, Queues.PAYMENT_WORKERS)).isZero();
    }

    @Test
    @DisplayName("A request whose queue message was lost is queued again from the database")
    void aLostQueueMessageIsRequeuedFromTheDatabase() throws Exception {
        submit("request-lost-message", BankScenario.APPROVE);
        deleteAllEntries(Queues.PAYMENT_REQUESTS);

        Thread.sleep(PAST_IDLE_TIMEOUT);
        queueWorkers.runMaintenance();

        assertThat(streamLength(Queues.PAYMENT_REQUESTS)).isEqualTo(1);
        driveUntilFinal("request-lost-message", false);
        assertThat(attempt("request-lost-message").getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("A bank timeout is retried with the same key and the payment succeeds exactly once")
    void aBankTimeoutIsRetriedWithTheSameKey() {
        submit("request-timeout", BankScenario.TIMEOUT_THEN_APPROVE);

        driveUntilFinal("request-timeout", true);

        PaymentAttempt attempt = attempt("request-timeout");
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt.getBankRequestCount()).isEqualTo(2);
        String bankKey = BankRequest.bankKey(paymentId, "request-timeout");
        assertThat(redis.opsForValue().get("bank:deliveries:" + bankKey)).isEqualTo("2");
        assertThat(receiptRepository.findByPaymentId(paymentId)).isPresent();
    }

    @Test
    @DisplayName("When the bank answers the same request twice, only the first answer counts")
    void duplicateBankRepliesAreAppliedOnlyOnce() {
        submit("request-duplicate-reply", BankScenario.DUPLICATE_REPLY);
        queueWorkers.pollOnce(Queues.PAYMENT_REQUESTS, "worker-a");
        queueWorkers.pollOnce(Queues.BANK_REQUESTS, "bank-1");
        await().atMost(WAIT).until(() -> streamLength(Queues.BANK_RESPONSES) == 2);

        // A second receipt would break the unique constraint and leave that reply pending, so both must be handled.
        assertThat(queueWorkers.pollOnce(Queues.BANK_RESPONSES, "worker-b")).isEqualTo(2);

        assertThat(attempt("request-duplicate-reply").getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(receiptRepository.findByPaymentId(paymentId)).isPresent();
        assertThat(streamLength(Queues.BANK_RESPONSES)).isZero();
        assertThat(pendingCount(Queues.BANK_RESPONSES, Queues.PAYMENT_WORKERS)).isZero();
    }

    @Test
    @DisplayName("A bank that never replies fails the attempt after the last retry, and a new key can try again")
    void aBankThatNeverRepliesFailsTheAttempt() {
        submit("request-no-reply", BankScenario.NO_REPLY);

        driveUntilFinal("request-no-reply", true);

        PaymentAttempt failed = attempt("request-no-reply");
        assertThat(failed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(failed.getDeclineReason()).isEqualTo("bank_timeout");
        assertThat(failed.getBankRequestCount()).isEqualTo(3);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.AWAITING_PAYMENT);

        submit("request-after-failure", BankScenario.APPROVE);
        driveUntilFinal("request-after-failure", false);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("A declined payment accepts a new request with a new key and ends with one receipt")
    void aDeclinedPaymentCanBeRetriedWithANewKey() {
        submit("request-declined", BankScenario.DECLINE);
        driveUntilFinal("request-declined", false);
        assertThat(attempt("request-declined").getStatus()).isEqualTo(PaymentAttemptStatus.DECLINED);
        assertThat(paymentStatus()).isEqualTo(PaymentStatus.AWAITING_PAYMENT);

        submit("request-second-try", BankScenario.APPROVE);
        driveUntilFinal("request-second-try", false);

        assertThat(paymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(attemptRepository.countByPaymentId(paymentId)).isEqualTo(2);
        assertThat(receiptRepository.findByPaymentId(paymentId).orElseThrow().getAttempt().getId())
                .isEqualTo(attempt("request-second-try").getId());
    }

    @Test
    @DisplayName("A message that keeps failing is moved to dead letters instead of blocking the queue")
    void aPoisonMessageIsMovedToDeadLetters() throws Exception {
        redis.opsForStream().add(Queues.PAYMENT_REQUESTS, Map.of("attemptId", "not-a-uuid"));
        queueWorkers.pollOnce(Queues.PAYMENT_REQUESTS, "worker-a");
        assertThat(pendingCount(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS)).isEqualTo(1);

        for (int i = 0; i < 4 && streamLength(Queues.DEAD_LETTERS) == 0; i++) {
            Thread.sleep(PAST_IDLE_TIMEOUT);
            queueWorkers.runMaintenance();
        }

        assertThat(streamLength(Queues.DEAD_LETTERS)).isEqualTo(1);
        assertThat(pendingCount(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS)).isZero();
        assertThat(streamLength(Queues.PAYMENT_REQUESTS)).isZero();
    }

    @Test
    @DisplayName("An older status write arriving late cannot overwrite a newer status in Redis")
    void staleStatusWritesAreIgnored() {
        PaymentRequestStatus succeeded = status(PaymentAttemptStatus.SUCCEEDED, 3);
        PaymentRequestStatus pending = status(PaymentAttemptStatus.PENDING, 2);

        assertThat(statusStore.save(succeeded)).isTrue();
        assertThat(statusStore.save(pending)).isFalse();

        assertThat(statusStore.find(paymentId, "request-versioned").orElseThrow().status())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("When Redis has lost a status, the lookup reads the database and refills Redis")
    void statusLookupFallsBackToTheDatabase() {
        submit("request-cache-miss", BankScenario.APPROVE);
        redis.delete(PaymentRequestStatusStore.key(paymentId, "request-cache-miss"));

        PaymentRequestStatusResponse fromDatabase =
                paymentRequestService.getStatus(merchantId, paymentId, "request-cache-miss");
        PaymentRequestStatusResponse fromCache =
                paymentRequestService.getStatus(merchantId, paymentId, "request-cache-miss");

        assertThat(fromDatabase.source()).isEqualTo("database");
        assertThat(fromCache.source()).isEqualTo("cache");
        assertThat(fromCache.attemptId()).isEqualTo(fromDatabase.attemptId());
    }

    private UUID newPayment() {
        return paymentService.createPayment(
                merchantId, "payment-" + UUID.randomUUID(),
                new CreatePaymentRequest(1000L, "INR", "Behaviour test", null)).id();
    }

    private PaymentRequestService.Submission submit(String key, BankScenario scenario) {
        return paymentRequestService.submit(merchantId, paymentId, key, new CreatePaymentAttemptRequest(scenario));
    }

    // Plays the part of the background workers until the attempt is final.
    private void driveUntilFinal(String key, boolean withRecovery) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(25)).until(() -> {
            queueWorkers.pollOnce(Queues.PAYMENT_REQUESTS, "worker-a");
            queueWorkers.pollOnce(Queues.BANK_REQUESTS, "bank-1");
            queueWorkers.pollOnce(Queues.BANK_RESPONSES, "worker-b");
            if (withRecovery) {
                queueWorkers.runMaintenance();
            }
            return attempt(key).getStatus().isFinal();
        });
    }

    private PaymentAttempt attempt(String key) {
        return transactionTemplate.execute(tx -> {
            PaymentAttempt attempt = attemptRepository.findByPaymentIdAndIdempotencyKey(paymentId, key).orElseThrow();
            attempt.getPayment().getId();
            return attempt;
        });
    }

    private PaymentStatus paymentStatus() {
        return paymentRepository.findById(paymentId).orElseThrow().getStatus();
    }

    private PaymentAttemptStatus cachedStatus(String key) {
        return statusStore.find(paymentId, key).orElseThrow().status();
    }

    private PaymentRequestStatus status(PaymentAttemptStatus status, long version) {
        return new PaymentRequestStatus(
                UUID.randomUUID(), paymentId, merchantId, "request-versioned", BankScenario.APPROVE, status,
                null, 1, null, version, Instant.now());
    }

    private long streamLength(String stream) {
        Long size = redis.opsForStream().size(stream);
        return size == null ? 0 : size;
    }

    private long pendingCount(String stream, String group) {
        return redis.opsForStream().pending(stream, group, Range.unbounded(), 100).size();
    }

    @SuppressWarnings("unchecked")
    private void readWithoutHandling(String stream, String group, String consumer) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer), StreamOffset.create(stream, ReadOffset.lastConsumed()));
        assertThat(records).hasSize(1);
    }

    private void deleteAllEntries(String stream) {
        redis.opsForStream().range(stream, Range.unbounded())
                .forEach(record -> redis.opsForStream().delete(stream, record.getId()));
        assertThat(streamLength(stream)).isZero();
    }
}
