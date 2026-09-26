package com.paymentsystem.models;

// A declined attempt returns the payment to AWAITING_PAYMENT so the customer can retry.
public enum PaymentStatus {
    AWAITING_PAYMENT,
    PROCESSING,
    SUCCEEDED,
    CANCELLED
}
