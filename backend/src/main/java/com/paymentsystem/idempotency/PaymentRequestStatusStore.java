package com.paymentsystem.idempotency;

import com.paymentsystem.config.ProcessingProperties;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

// Redis copy of each payment request's status. The database stays authoritative; readers fall back to it on a miss.
@Component
@RequiredArgsConstructor
public class PaymentRequestStatusStore {

    // Workers finish out of order, so a write carrying an older attempt version must not replace a newer one.
    private static final RedisScript<Long> SAVE_IF_NOT_OLDER = RedisScript.of("""
            local current = redis.call('HGET', KEYS[1], 'version')
            if current and tonumber(current) > tonumber(ARGV[1]) then
              return 0
            end
            redis.call('HSET', KEYS[1], unpack(ARGV, 3))
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ProcessingProperties properties;

    public boolean save(PaymentRequestStatus status) {
        List<String> args = new ArrayList<>();
        args.add(Long.toString(status.version()));
        args.add(Long.toString(properties.statusTtl().toMillis()));
        put(args, "attemptId", status.attemptId());
        put(args, "paymentId", status.paymentId());
        put(args, "merchantId", status.merchantId());
        put(args, "idempotencyKey", status.idempotencyKey());
        put(args, "scenario", status.scenario());
        put(args, "bank", status.bank());
        put(args, "status", status.status());
        put(args, "declineReason", status.declineReason());
        put(args, "bankRequestCount", status.bankRequestCount());
        put(args, "receiptNumber", status.receiptNumber());
        put(args, "version", status.version());
        put(args, "updatedAt", status.updatedAt());
        Long written = redis.execute(
                SAVE_IF_NOT_OLDER, List.of(key(status.paymentId(), status.idempotencyKey())), args.toArray());
        return written != null && written == 1;
    }

    public Optional<PaymentRequestStatus> find(UUID paymentId, String idempotencyKey) {
        Map<Object, Object> fields = redis.opsForHash().entries(key(paymentId, idempotencyKey));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PaymentRequestStatus(
                UUID.fromString(text(fields, "attemptId")),
                UUID.fromString(text(fields, "paymentId")),
                UUID.fromString(text(fields, "merchantId")),
                text(fields, "idempotencyKey"),
                BankScenario.valueOf(text(fields, "scenario")),
                text(fields, "bank"),
                PaymentAttemptStatus.valueOf(text(fields, "status")),
                text(fields, "declineReason"),
                Integer.parseInt(text(fields, "bankRequestCount")),
                text(fields, "receiptNumber"),
                Long.parseLong(text(fields, "version")),
                Instant.parse(text(fields, "updatedAt"))));
    }

    public static String key(UUID paymentId, String idempotencyKey) {
        return "payment-request:" + paymentId + ":" + idempotencyKey;
    }

    private static void put(List<String> args, String field, Object value) {
        args.add(field);
        args.add(value == null ? "" : value.toString());
    }

    private static String text(Map<Object, Object> fields, String field) {
        Object value = fields.get(field);
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }
}
