package com.paymentsystem.queue;

import java.util.Map;
import java.util.UUID;

public record PaymentRequestMessage(UUID attemptId, UUID paymentId, String idempotencyKey) {

    public Map<String, String> toFields() {
        return Map.of(
                "attemptId", attemptId.toString(),
                "paymentId", paymentId.toString(),
                "idempotencyKey", idempotencyKey);
    }

    public static PaymentRequestMessage fromFields(Map<String, String> fields) {
        return new PaymentRequestMessage(
                UUID.fromString(fields.get("attemptId")),
                UUID.fromString(fields.get("paymentId")),
                fields.get("idempotencyKey"));
    }
}
