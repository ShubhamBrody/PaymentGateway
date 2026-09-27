package com.paymentsystem.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentsystem.dtos.AuthResponse;
import com.paymentsystem.dtos.CreatePaymentAttemptRequest;
import com.paymentsystem.dtos.CreatePaymentRequest;
import com.paymentsystem.dtos.LoginRequest;
import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.PaymentRequestStatusResponse;
import com.paymentsystem.dtos.PaymentResponse;
import com.paymentsystem.dtos.ReceiptResponse;
import com.paymentsystem.dtos.RegisterRequest;
import com.paymentsystem.dtos.SystemStatsResponse;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import com.paymentsystem.support.RedisTestContainerConfig;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

// End to end over real HTTP: the embedded server, background queue workers, the simulated bank, and Redis all run.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e2e-${random.uuid};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "app.processing.workers-enabled=true",
                "app.processing.worker-count=4",
                "app.processing.maintenance-interval=100ms",
                "app.processing.reclaim-idle-timeout=1s",
                "app.processing.requeue-after=1s",
                "app.processing.bank-reply-timeout=500ms",
                "app.bank.approve-delay=20ms",
                "app.bank.slow-delay=100ms"
        })
@ActiveProfiles("test")
@Import(RedisTestContainerConfig.class)
class PaymentSystemEndToEndTests {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String PASSWORD = "correct-horse-battery-staple";

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private ReceiptRepository receiptRepository;

    private RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("A merchant signs up, takes a payment through the queue, retries safely, and gets a receipt")
    void completePaymentJourneyOverHttp() {
        String token = signUpAndLogIn();
        PaymentResponse payment = createPayment(token, 125_000);
        String key = "checkout-" + UUID.randomUUID();

        ResponseEntity<PaymentRequestStatusResponse> accepted =
                submit(token, payment.id(), key, BankScenario.APPROVE, "BANK12");
        ResponseEntity<PaymentRequestStatusResponse> retried =
                submit(token, payment.id(), key, BankScenario.APPROVE, "BANK12");

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(retried.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retried.getBody().attemptId()).isEqualTo(accepted.getBody().attemptId());
        URI statusLocation = accepted.getHeaders().getLocation();
        assertThat(statusLocation).hasPath("/api/v1/payments/" + payment.id() + "/requests/" + key);

        PaymentRequestStatusResponse finalStatus = await().atMost(WAIT).until(
                () -> get(token, statusLocation.toString(), PaymentRequestStatusResponse.class),
                status -> status.status().isFinal());

        assertThat(finalStatus.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(finalStatus.bank()).isEqualTo("BANK12");
        ReceiptResponse receipt = get(token, "/api/v1/payments/" + payment.id() + "/receipt", ReceiptResponse.class);
        assertThat(receipt.receiptNumber()).isEqualTo(finalStatus.receiptNumber()).matches("RCPT-[0-9A-F]{12}");
        assertThat(receipt.amount()).isEqualTo(125_000);
        assertThat(receipt.attemptId()).isEqualTo(accepted.getBody().attemptId());
        assertThat(get(token, "/api/v1/payments/" + payment.id(), PaymentResponse.class).status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        PaymentAttemptResponse[] attempts = get(
                token, "/api/v1/payments/" + payment.id() + "/attempts", PaymentAttemptResponse[].class);
        assertThat(attempts).hasSize(1);
        assertThat(attempts[0].receiptNumber()).isEqualTo(receipt.receiptNumber());
    }

    @Test
    @DisplayName("Many merchants paying concurrently, with client retries and every bank behaviour, stay consistent")
    void concurrentMerchantsStayConsistent() throws Exception {
        int merchants = 5;
        int paymentsPerMerchant = 12;
        List<Future<List<UUID>>> work = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int m = 0; m < merchants; m++) {
                work.add(executor.submit(() -> {
                    String token = signUpAndLogIn();
                    List<UUID> paymentIds = new ArrayList<>();
                    for (int p = 0; p < paymentsPerMerchant; p++) {
                        UUID paymentId = createPayment(token, 1_000 + p).id();
                        String key = "load-" + UUID.randomUUID();
                        BankScenario scenario = BankScenario.values()[p % BankScenario.values().length];
                        if (scenario == BankScenario.NO_REPLY) {
                            scenario = BankScenario.RANDOM;
                        }
                        String bank = "BANK%02d".formatted(1 + p % 30);
                        ResponseEntity<PaymentRequestStatusResponse> first =
                                submit(token, paymentId, key, scenario, bank);
                        ResponseEntity<PaymentRequestStatusResponse> retry =
                                submit(token, paymentId, key, scenario, bank);
                        assertThat(retry.getBody().attemptId()).isEqualTo(first.getBody().attemptId());
                        paymentIds.add(paymentId);
                    }
                    return paymentIds;
                }));
            }
        }
        List<UUID> paymentIds = new ArrayList<>();
        for (Future<List<UUID>> future : work) {
            paymentIds.addAll(future.get());
        }

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() ->
                attemptRepository.countByStatus(PaymentAttemptStatus.QUEUED) == 0
                        && attemptRepository.countByStatus(PaymentAttemptStatus.PENDING) == 0);

        assertThat(paymentIds).hasSize(merchants * paymentsPerMerchant);
        for (UUID paymentId : paymentIds) {
            PaymentStatus status = paymentRepository.findById(paymentId).orElseThrow().getStatus();
            assertThat(attemptRepository.countByPaymentId(paymentId)).isEqualTo(1);
            assertThat(status).isIn(PaymentStatus.SUCCEEDED, PaymentStatus.AWAITING_PAYMENT);
            assertThat(receiptRepository.findByPaymentId(paymentId).isPresent())
                    .isEqualTo(status == PaymentStatus.SUCCEEDED);
        }
        String token = signUpAndLogIn();
        SystemStatsResponse stats = await().atMost(WAIT).until(
                () -> get(token, "/api/v1/simulator/stats", SystemStatsResponse.class),
                current -> current.queues().stream()
                        .filter(queue -> !queue.stream().equals("payments:dead-letters"))
                        .allMatch(queue -> queue.length() == 0 && queue.pending() == 0));
        assertThat(stats.receipts()).isEqualTo(stats.payments().get(PaymentStatus.SUCCEEDED));
        assertThat(stats.attemptsByBank()).containsKeys("BANK01", "BANK12");
    }

    private String signUpAndLogIn() {
        String email = "merchant-" + UUID.randomUUID() + "@example.com";
        http.post().uri("/api/v1/auth/register")
                .body(new RegisterRequest("E2E Store", email, PASSWORD))
                .retrieve()
                .toBodilessEntity();
        AuthResponse auth = http.post().uri("/api/v1/auth/login")
                .body(new LoginRequest(email, PASSWORD))
                .retrieve()
                .body(AuthResponse.class);
        return Objects.requireNonNull(auth).accessToken();
    }

    private PaymentResponse createPayment(String token, long amount) {
        return http.post().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "payment-" + UUID.randomUUID())
                .body(new CreatePaymentRequest(amount, "INR", "E2E order", null))
                .retrieve()
                .body(PaymentResponse.class);
    }

    private ResponseEntity<PaymentRequestStatusResponse> submit(
            String token, UUID paymentId, String key, BankScenario scenario, String bank) {
        return http.post().uri("/api/v1/payments/{paymentId}/attempts", paymentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", key)
                .body(new CreatePaymentAttemptRequest(scenario, bank))
                .retrieve()
                .toEntity(PaymentRequestStatusResponse.class);
    }

    private <T> T get(String token, String path, Class<T> type) {
        return http.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(type);
    }
}
