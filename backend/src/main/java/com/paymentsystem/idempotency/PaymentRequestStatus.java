package com.paymentsystem.idempotency;

import com.paymentsystem.dtos.PaymentRequestStatusResponse;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Instant;
import java.util.UUID;

// Snapshot of one payment request, keyed by its idempotency key. version is the attempt row's @Version.
public record PaymentRequestStatus(
        UUID attemptId,
        UUID paymentId,
        UUID merchantId,
        String idempotencyKey,
        BankScenario scenario,
        PaymentAttemptStatus status,
        String declineReason,
        int bankRequestCount,
        String receiptNumber,
        long version,
        Instant updatedAt) {

    public static PaymentRequestStatus of(PaymentAttempt attempt, String receiptNumber) {
        return new PaymentRequestStatus(
                attempt.getId(),
                attempt.getPayment().getId(),
                attempt.getPayment().getMerchant().getId(),
                attempt.getIdempotencyKey(),
                attempt.getScenario(),
                attempt.getStatus(),
                attempt.getDeclineReason(),
                attempt.getBankRequestCount(),
                receiptNumber,
                attempt.getVersion(),
                Instant.now());
    }

    public PaymentRequestStatusResponse toResponse(String source) {
        return new PaymentRequestStatusResponse(
                attemptId,
                paymentId,
                idempotencyKey,
                scenario,
                status,
                declineReason,
                bankRequestCount,
                receiptNumber,
                updatedAt,
                source);
    }
}
