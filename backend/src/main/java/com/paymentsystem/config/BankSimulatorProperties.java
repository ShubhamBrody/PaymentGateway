package com.paymentsystem.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.bank")
public record BankSimulatorProperties(
        boolean simulatorEnabled,
        @NotNull Duration approveDelay,
        @NotNull Duration slowDelay) {
}
