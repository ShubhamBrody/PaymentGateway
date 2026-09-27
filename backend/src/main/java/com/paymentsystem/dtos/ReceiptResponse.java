package com.paymentsystem.dtos;

import com.paymentsystem.models.Receipt;
import java.time.Instant;
import java.util.UUID;

public record ReceiptResponse(
        String receiptNumber,
        UUID paymentId,
        UUID attemptId,
        long amount,
        String currency,
        String bankReference,
        Instant issuedAt) {

    public static ReceiptResponse from(Receipt receipt) {
        return new ReceiptResponse(
                receipt.getReceiptNumber(),
                receipt.getPayment().getId(),
                receipt.getAttempt().getId(),
                receipt.getAmount(),
                receipt.getCurrency(),
                receipt.getBankReference(),
                receipt.getIssuedAt());
    }
}
