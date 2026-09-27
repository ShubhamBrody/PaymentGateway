package com.paymentsystem.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.processing")
public record ProcessingProperties(
        boolean workersEnabled,
        @Min(1) int workerCount,
        @Min(1) int batchSize,
        @NotNull Duration idlePollInterval,
        @NotNull Duration maintenanceInterval,
        @NotNull Duration reclaimIdleTimeout,
        @Min(1) int maxDeliveries,
        @NotNull Duration requeueAfter,
        @NotNull Duration bankReplyTimeout,
        @Min(1) int maxBankRequests,
        @NotNull Duration statusTtl) {
}
