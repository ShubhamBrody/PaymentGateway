package com.paymentsystem.dtos;

import com.paymentsystem.models.BankScenario;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// bank is the customer's bank code, such as BANK07. Without one, the request goes to the first bank.
public record CreatePaymentAttemptRequest(
        @NotNull BankScenario scenario,
        @Pattern(regexp = "[A-Z0-9]{2,10}") String bank) {
}
