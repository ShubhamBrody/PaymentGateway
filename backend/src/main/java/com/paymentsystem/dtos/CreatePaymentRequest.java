package com.paymentsystem.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
        // Smallest currency unit, e.g. 1000 paise = INR 10.00.
        @NotNull @Positive @Max(100_000_000) Long amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter uppercase ISO 4217 code")
        String currency,
        @Size(max = 255) String description,
        @Size(max = 64) String orderReference) {
}
