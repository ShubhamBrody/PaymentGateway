package com.paymentsystem.dtos;

import com.paymentsystem.models.BankScenario;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentAttemptRequest(@NotNull BankScenario scenario) {
}
