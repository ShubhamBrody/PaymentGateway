package com.paymentsystem.models;

// Chosen per attempt to tell the simulated bank how to respond; RANDOM is resolved once per request.
public enum BankScenario {
    APPROVE,
    DECLINE,
    SLOW_APPROVE,
    TIMEOUT_THEN_APPROVE,
    DUPLICATE_REPLY,
    NO_REPLY,
    RANDOM
}
