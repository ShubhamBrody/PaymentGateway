package com.paymentsystem.dtos;

import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        long amount,
        String currency,
        PaymentStatus status,
        String description,
        String orderReference,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getDescription(),
                payment.getOrderReference(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
