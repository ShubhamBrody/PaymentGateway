package com.paymentsystem.behaviour;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsystem.dtos.CreatePaymentAttemptRequest;
import com.paymentsystem.dtos.CreatePaymentRequest;
import com.paymentsystem.dtos.PaymentRequestStatusResponse;
import com.paymentsystem.dtos.RegisterRequest;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.services.AuthService;
import com.paymentsystem.services.PaymentRequestService;
import com.paymentsystem.services.PaymentService;
import com.paymentsystem.workers.QueueWorkers;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// The backend with nothing listening on the Redis port: H2 alone must be enough to start, accept payment
// requests, and answer their status. The context is closed afterwards so its retrying workers stop.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:redis-down-${random.uuid};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.connect-timeout=200ms",
        "app.processing.workers-enabled=true",
        "app.processing.worker-count=1",
        "app.bank.simulator-enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext
class RedisDownBehaviourTests {

    private static final CreatePaymentAttemptRequest APPROVE = new CreatePaymentAttemptRequest(BankScenario.APPROVE);

    @DynamicPropertySource
    static void redisIsDown(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            int closedPort = socket.getLocalPort();
            registry.add("spring.data.redis.port", () -> closedPort);
        }
    }

    @Autowired
    private QueueWorkers queueWorkers;

    @Autowired
    private AuthService authService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRequestService paymentRequestService;

    @Test
    @DisplayName("With Redis down, the backend starts, accepts payment requests, and answers their status from H2")
    void paymentRequestsAreAcceptedAndAnsweredFromTheDatabase() {
        assertThat(queueWorkers.isRunning()).isTrue();
        UUID merchantId = authService.register(new RegisterRequest(
                "Redis Down Store", "merchant-" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple"))
                .id();
        UUID paymentId = paymentService.createPayment(
                merchantId, "payment-" + UUID.randomUUID(),
                new CreatePaymentRequest(1000L, "INR", "Redis down", null)).id();

        PaymentRequestService.Submission accepted =
                paymentRequestService.submit(merchantId, paymentId, "request-redis-down", APPROVE);
        PaymentRequestStatusResponse status =
                paymentRequestService.getStatus(merchantId, paymentId, "request-redis-down");
        PaymentRequestService.Submission retried =
                paymentRequestService.submit(merchantId, paymentId, "request-redis-down", APPROVE);

        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.status().status()).isEqualTo(PaymentAttemptStatus.QUEUED);
        assertThat(status.status()).isEqualTo(PaymentAttemptStatus.QUEUED);
        assertThat(status.source()).isEqualTo("database");
        assertThat(retried.replayed()).isTrue();
        assertThat(retried.status().attemptId()).isEqualTo(accepted.status().attemptId());
    }
}
