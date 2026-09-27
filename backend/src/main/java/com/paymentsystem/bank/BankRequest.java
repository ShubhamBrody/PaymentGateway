package com.paymentsystem.bank;

import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttempt;
import java.util.Map;
import java.util.UUID;

// bankKey is the payment request's idempotency key, namespaced by payment, and is identical on every retry.
// bank is the code of the customer's bank that should answer, such as BANK07.
public record BankRequest(
        String bankKey,
        UUID attemptId,
        UUID paymentId,
        String bank,
        long amount,
        String currency,
        BankScenario scenario) {

    public static BankRequest from(PaymentAttempt attempt) {
        return new BankRequest(
                bankKey(attempt.getPayment().getId(), attempt.getIdempotencyKey()),
                attempt.getId(),
                attempt.getPayment().getId(),
                attempt.getBankCode(),
                attempt.getPayment().getAmount(),
                attempt.getPayment().getCurrency(),
                attempt.getScenario());
    }

    public static String bankKey(UUID paymentId, String idempotencyKey) {
        return paymentId + ":" + idempotencyKey;
    }

    public Map<String, String> toFields() {
        return Map.of(
                "bankKey", bankKey,
                "attemptId", attemptId.toString(),
                "paymentId", paymentId.toString(),
                "bank", bank,
                "amount", Long.toString(amount),
                "currency", currency,
                "scenario", scenario.name());
    }

    public static BankRequest fromFields(Map<String, String> fields) {
        return new BankRequest(
                fields.get("bankKey"),
                UUID.fromString(fields.get("attemptId")),
                UUID.fromString(fields.get("paymentId")),
                fields.get("bank"),
                Long.parseLong(fields.get("amount")),
                fields.get("currency"),
                BankScenario.valueOf(fields.get("scenario")));
    }
}
