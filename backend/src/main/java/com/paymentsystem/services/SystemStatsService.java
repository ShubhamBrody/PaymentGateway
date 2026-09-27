package com.paymentsystem.services;

import com.paymentsystem.dtos.SystemStatsResponse;
import com.paymentsystem.dtos.SystemStatsResponse.QueueStats;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.queue.Queues;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemStatsService {

    private final StringRedisTemplate redis;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReceiptRepository receiptRepository;

    @Transactional(readOnly = true)
    public SystemStatsResponse stats() {
        List<QueueStats> queues = List.of(
                queue(Queues.PAYMENT_REQUESTS, Queues.PAYMENT_WORKERS),
                queue(Queues.BANK_REQUESTS, Queues.BANK_SIMULATOR),
                queue(Queues.BANK_RESPONSES, Queues.PAYMENT_WORKERS),
                queue(Queues.DEAD_LETTERS, null));
        return new SystemStatsResponse(
                queues,
                counts(paymentRepository.countByStatus(), PaymentStatus.class),
                counts(attemptRepository.countByStatus(), PaymentAttemptStatus.class),
                receiptRepository.count(),
                Instant.now());
    }

    private QueueStats queue(String stream, String group) {
        Long length = redis.opsForStream().size(stream);
        long pending = 0;
        if (group != null && length != null) {
            try {
                pending = redis.opsForStream().pending(stream, group).getTotalPendingMessages();
            } catch (RuntimeException ex) {
                // The consumer group does not exist until workers start.
                pending = 0;
            }
        }
        return new QueueStats(stream, length == null ? 0 : length, pending);
    }

    private static <E extends Enum<E>> Map<E, Long> counts(List<Object[]> rows, Class<E> type) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counts.put(value, 0L);
        }
        for (Object[] row : rows) {
            counts.put(type.cast(row[0]), (Long) row[1]);
        }
        return counts;
    }
}
