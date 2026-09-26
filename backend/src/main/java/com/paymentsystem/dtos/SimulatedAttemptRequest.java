package com.paymentsystem.dtos;

import com.paymentsystem.models.PaymentAttemptStatus;
import jakarta.validation.constraints.NotNull;

public record SimulatedAttemptRequest(@NotNull PaymentAttemptStatus outcome) {
}
