package com.paymentsystem.dtos;

import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SystemStatsResponse(
        List<QueueStats> queues,
        Map<PaymentStatus, Long> payments,
        Map<PaymentAttemptStatus, Long> attempts,
        Map<String, Map<PaymentAttemptStatus, Long>> attemptsByBank,
        long receipts,
        Instant generatedAt) {

    // length counts unhandled entries; pending counts entries delivered to a worker but not yet acknowledged.
    public record QueueStats(String stream, long length, long pending) {
    }
}
