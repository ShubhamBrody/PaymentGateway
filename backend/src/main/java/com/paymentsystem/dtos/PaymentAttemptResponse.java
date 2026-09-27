package com.paymentsystem.dtos;

import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptResponse(
        UUID id,
        UUID paymentId,
        String idempotencyKey,
        BankScenario scenario,
        PaymentAttemptStatus status,
        String declineReason,
        String bankReference,
        int bankRequestCount,
        String receiptNumber,
        Instant createdAt,
        Instant resolvedAt) {

    public static PaymentAttemptResponse from(PaymentAttempt attempt, String receiptNumber) {
        return new PaymentAttemptResponse(
                attempt.getId(),
                attempt.getPayment().getId(),
                attempt.getIdempotencyKey(),
                attempt.getScenario(),
                attempt.getStatus(),
                attempt.getDeclineReason(),
                attempt.getBankReference(),
                attempt.getBankRequestCount(),
                receiptNumber,
                attempt.getCreatedAt(),
                attempt.getResolvedAt());
    }
}
