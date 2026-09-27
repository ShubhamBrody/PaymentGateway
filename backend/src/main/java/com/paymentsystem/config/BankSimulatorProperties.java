package com.paymentsystem.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.bank")
public record BankSimulatorProperties(
        boolean simulatorEnabled,
        @Min(1) @Max(99) int banks,
        @NotNull Duration approveDelay,
        @NotNull Duration slowDelay,
        @NotNull Duration memoryTtl) {
}
