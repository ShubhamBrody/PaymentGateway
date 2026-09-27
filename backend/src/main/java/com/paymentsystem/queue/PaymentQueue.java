package com.paymentsystem.queue;

import com.paymentsystem.bank.BankRequest;
import com.paymentsystem.bank.BankResponse;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentQueue {

    // Handled entries are deleted, so this cap only guards against runaway growth if workers stop.
    private static final XAddOptions CAPPED = XAddOptions.trim(TrimOptions.maxLen(1_000_000).approximate());

    private final StringRedisTemplate redis;

    public void publishPaymentRequest(PaymentRequestMessage message) {
        add(Queues.PAYMENT_REQUESTS, message.toFields());
    }

    public void publishBankRequest(BankRequest request) {
        add(Queues.BANK_REQUESTS, request.toFields());
    }

    public void publishBankResponse(BankResponse response) {
        add(Queues.BANK_RESPONSES, response.toFields());
    }

    public void publishDeadLetter(String stream, Map<String, String> fields, String reason) {
        Map<String, String> deadLetter = new HashMap<>(fields);
        deadLetter.put("sourceStream", stream);
        deadLetter.put("reason", reason);
        add(Queues.DEAD_LETTERS, deadLetter);
    }

    public void acknowledgeAndDelete(String stream, String group, RecordId id) {
        redis.opsForStream().acknowledge(stream, group, id);
        redis.opsForStream().delete(stream, id);
    }

    private void add(String stream, Map<String, String> fields) {
        redis.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(fields), CAPPED);
    }
}
