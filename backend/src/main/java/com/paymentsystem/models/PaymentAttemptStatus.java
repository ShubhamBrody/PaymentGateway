package com.paymentsystem.models;

// QUEUED waits for a worker; PENDING was sent to the bank and waits for its reply.
public enum PaymentAttemptStatus {
    QUEUED,
    PENDING,
    SUCCEEDED,
    DECLINED,
    FAILED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == DECLINED || this == FAILED;
    }
}
