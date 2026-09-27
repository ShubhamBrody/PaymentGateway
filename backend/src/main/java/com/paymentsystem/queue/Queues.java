package com.paymentsystem.queue;

public final class Queues {

    public static final String PAYMENT_REQUESTS = "payments:requests";
    public static final String BANK_REQUESTS = "bank:requests";
    public static final String BANK_RESPONSES = "bank:responses";
    public static final String DEAD_LETTERS = "payments:dead-letters";

    public static final String PAYMENT_WORKERS = "payment-workers";
    public static final String BANK_SIMULATOR = "bank-simulator";

    private Queues() {
    }
}
