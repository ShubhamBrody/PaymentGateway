package com.paymentsystem.services;

import com.paymentsystem.bank.BankDirectory;
import com.paymentsystem.config.ProcessingProperties;
import com.paymentsystem.dtos.CreatePaymentAttemptRequest;
import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.PaymentRequestStatusResponse;
import com.paymentsystem.dtos.ReceiptResponse;
import com.paymentsystem.exceptions.ConflictException;
import com.paymentsystem.exceptions.InvalidRequestException;
import com.paymentsystem.exceptions.ResourceNotFoundException;
import com.paymentsystem.idempotency.PaymentRequestStatus;
import com.paymentsystem.idempotency.PaymentRequestStatusStore;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.models.Receipt;
import com.paymentsystem.queue.PaymentQueue;
import com.paymentsystem.queue.PaymentRequestMessage;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// API side of a payment request: record it durably, queue it, and answer from Redis when the same key returns.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestService {

    public record Submission(PaymentRequestStatusResponse status, boolean replayed) {
    }

    private record Recorded(PaymentRequestStatus status, boolean created) {
    }

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReceiptRepository receiptRepository;
    private final PaymentQueue paymentQueue;
    private final PaymentRequestStatusStore statusStore;
    private final TransactionTemplate transactionTemplate;
    private final ProcessingProperties properties;
    private final BankDirectory bankDirectory;

    // Set after a Redis error so the next calls go straight to the database instead of each waiting on Redis.
    private volatile Instant skipRedisUntil = Instant.MIN;

    public Submission submit(
            UUID merchantId, UUID paymentId, String idempotencyKey, CreatePaymentAttemptRequest request) {
        String bank = bankCode(request.bank());
        Optional<PaymentRequestStatus> cached = findCached(paymentId, idempotencyKey);
        if (cached.isPresent()) {
            PaymentRequestStatus status = requireSameRequest(cached.get(), merchantId, request.scenario(), bank);
            return new Submission(status.toResponse("cache"), true);
        }

        Recorded recorded = transactionTemplate.execute(
                tx -> record(merchantId, paymentId, idempotencyKey, request.scenario(), bank));
        if (recorded.created()) {
            publishAfterCommit(recorded.status());
            return new Submission(recorded.status().toResponse("database"), false);
        }
        cache(recorded.status());
        return new Submission(recorded.status().toResponse("database"), true);
    }

    public PaymentRequestStatusResponse getStatus(UUID merchantId, UUID paymentId, String idempotencyKey) {
        Optional<PaymentRequestStatus> cached = findCached(paymentId, idempotencyKey);
        if (cached.isPresent() && cached.get().merchantId().equals(merchantId)) {
            return cached.get().toResponse("cache");
        }
        PaymentRequestStatus status = transactionTemplate.execute(tx -> attemptRepository
                .findByPaymentIdAndIdempotencyKeyAndPaymentMerchantId(paymentId, idempotencyKey, merchantId)
                .map(attempt -> PaymentRequestStatus.of(attempt, receiptNumber(attempt)))
                .orElseThrow(() -> new ResourceNotFoundException("Payment request not found")));
        cache(status);
        return status.toResponse("database");
    }

    public PaymentAttemptResponse getAttempt(UUID merchantId, UUID paymentId, UUID attemptId) {
        return transactionTemplate.execute(tx -> attemptRepository
                .findByIdAndPaymentIdAndPaymentMerchantId(attemptId, paymentId, merchantId)
                .map(attempt -> PaymentAttemptResponse.from(attempt, receiptNumber(attempt)))
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found")));
    }

    public ReceiptResponse getReceipt(UUID merchantId, UUID paymentId) {
        return transactionTemplate.execute(tx -> receiptRepository
                .findByPaymentIdAndPaymentMerchantId(paymentId, merchantId)
                .map(ReceiptResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found")));
    }

    private Recorded record(
            UUID merchantId, UUID paymentId, String idempotencyKey, BankScenario scenario, String bank) {
        // The row lock also serializes concurrent retries of one key, so only the first creates an attempt.
        Payment payment = paymentRepository.findForUpdateByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        Optional<PaymentAttempt> existing = attemptRepository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey);
        if (existing.isPresent()) {
            PaymentRequestStatus status = PaymentRequestStatus.of(existing.get(), receiptNumber(existing.get()));
            return new Recorded(requireSameRequest(status, merchantId, scenario, bank), false);
        }
        String conflict = switch (payment.getStatus()) {
            case AWAITING_PAYMENT -> null;
            case PROCESSING -> "Another payment request for this payment is still in progress";
            case SUCCEEDED -> "The payment has already succeeded";
            case CANCELLED -> "A cancelled payment cannot accept payment requests";
        };
        if (conflict != null) {
            throw new ConflictException(conflict);
        }
        PaymentAttempt attempt = payment.addAttempt(idempotencyKey, scenario, bank);
        attempt.setLastQueuedAt(Instant.now());
        payment.setStatus(PaymentStatus.PROCESSING);
        attemptRepository.saveAndFlush(attempt);
        return new Recorded(PaymentRequestStatus.of(attempt, null), true);
    }

    private void publishAfterCommit(PaymentRequestStatus status) {
        if (redisAvailable()) {
            try {
                paymentQueue.publishPaymentRequest(
                        new PaymentRequestMessage(status.attemptId(), status.paymentId(), status.idempotencyKey()));
            } catch (RuntimeException ex) {
                // Already committed as QUEUED; the recovery job requeues it, so the client still gets 202.
                redisFailed("Could not queue payment request " + status.attemptId(), ex);
            }
        }
        cache(status);
    }

    private Optional<PaymentRequestStatus> findCached(UUID paymentId, String idempotencyKey) {
        if (!redisAvailable()) {
            return Optional.empty();
        }
        try {
            // A copy that is not final may be missing an update that Redis lost, so it is trusted only while recent.
            Instant recheckBefore = Instant.now().minus(properties.statusRecheckAfter());
            return statusStore.find(paymentId, idempotencyKey)
                    .filter(status -> status.status().isFinal() || status.updatedAt().isAfter(recheckBefore));
        } catch (RuntimeException ex) {
            redisFailed("Status cache unavailable", ex);
            return Optional.empty();
        }
    }

    private void cache(PaymentRequestStatus status) {
        if (!redisAvailable()) {
            return;
        }
        try {
            statusStore.save(status);
        } catch (RuntimeException ex) {
            redisFailed("Could not cache status for " + status.attemptId(), ex);
        }
    }

    private boolean redisAvailable() {
        return Instant.now().isAfter(skipRedisUntil);
    }

    private void redisFailed(String problem, RuntimeException ex) {
        skipRedisUntil = Instant.now().plus(properties.redisRetryAfter());
        log.warn("{}, answering from the database for the next {} ms: {}",
                problem, properties.redisRetryAfter().toMillis(), ex.toString());
    }

    private String bankCode(String requested) {
        if (requested == null) {
            return bankDirectory.defaultBank().code();
        }
        return bankDirectory.find(requested)
                .orElseThrow(() -> new InvalidRequestException("Unknown bank " + requested))
                .code();
    }

    private static PaymentRequestStatus requireSameRequest(
            PaymentRequestStatus status, UUID merchantId, BankScenario scenario, String bank) {
        if (!status.merchantId().equals(merchantId)) {
            throw new ResourceNotFoundException("Payment not found");
        }
        if (status.scenario() != scenario || !bank.equals(status.bank())) {
            throw new ConflictException("This Idempotency-Key was already used with a different payment request");
        }
        return status;
    }

    private String receiptNumber(PaymentAttempt attempt) {
        if (attempt.getStatus() != PaymentAttemptStatus.SUCCEEDED) {
            return null;
        }
        return receiptRepository.findByAttemptId(attempt.getId()).map(Receipt::getReceiptNumber).orElse(null);
    }
}
