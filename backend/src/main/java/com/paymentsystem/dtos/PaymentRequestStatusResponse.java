package com.paymentsystem.dtos;

import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Instant;
import java.util.UUID;

// source is "cache" when served from Redis and "database" when Redis had no entry.
public record PaymentRequestStatusResponse(
        UUID attemptId,
        UUID paymentId,
        String idempotencyKey,
        BankScenario scenario,
        PaymentAttemptStatus status,
        String declineReason,
        int bankRequestCount,
        String receiptNumber,
        Instant updatedAt,
        String source) {
}
