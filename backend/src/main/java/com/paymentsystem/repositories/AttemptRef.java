package com.paymentsystem.repositories;

import java.util.UUID;

public record AttemptRef(UUID attemptId, UUID paymentId, String idempotencyKey) {
}
