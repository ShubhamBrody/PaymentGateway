package com.paymentsystem.dtos;

import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptResponse(
        UUID id,
        UUID paymentId,
        PaymentAttemptStatus status,
        String declineReason,
        Instant createdAt,
        Instant resolvedAt) {

    public static PaymentAttemptResponse from(PaymentAttempt attempt) {
        return new PaymentAttemptResponse(
                attempt.getId(),
                attempt.getPayment().getId(),
                attempt.getStatus(),
                attempt.getDeclineReason(),
                attempt.getCreatedAt(),
                attempt.getResolvedAt());
    }
}
