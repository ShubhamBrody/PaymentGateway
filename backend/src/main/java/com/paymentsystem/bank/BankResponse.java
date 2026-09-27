package com.paymentsystem.bank;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record BankResponse(
        String bankKey,
        UUID attemptId,
        UUID paymentId,
        BankOutcome outcome,
        String bankReference,
        String declineReason) {

    public Map<String, String> toFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("bankKey", bankKey);
        fields.put("attemptId", attemptId.toString());
        fields.put("paymentId", paymentId.toString());
        fields.put("outcome", outcome.name());
        fields.put("bankReference", bankReference);
        if (declineReason != null) {
            fields.put("declineReason", declineReason);
        }
        return fields;
    }

    public static BankResponse fromFields(Map<String, String> fields) {
        return new BankResponse(
                fields.get("bankKey"),
                UUID.fromString(fields.get("attemptId")),
                UUID.fromString(fields.get("paymentId")),
                BankOutcome.valueOf(fields.get("outcome")),
                fields.get("bankReference"),
                fields.get("declineReason"));
    }
}
