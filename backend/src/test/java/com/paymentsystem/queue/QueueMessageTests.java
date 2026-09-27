package com.paymentsystem.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsystem.bank.BankOutcome;
import com.paymentsystem.bank.BankRequest;
import com.paymentsystem.bank.BankResponse;
import com.paymentsystem.models.BankScenario;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueMessageTests {

    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    @Test
    void paymentRequestMessageRoundTripsThroughStreamFields() {
        PaymentRequestMessage message = new PaymentRequestMessage(ATTEMPT_ID, PAYMENT_ID, "request-key-1");

        assertThat(PaymentRequestMessage.fromFields(message.toFields())).isEqualTo(message);
    }

    @Test
    void bankRequestRoundTripsAndUsesTheSameKeyForEveryRetry() {
        BankRequest request = new BankRequest(
                BankRequest.bankKey(PAYMENT_ID, "request-key-1"), ATTEMPT_ID, PAYMENT_ID, "BANK07", 1000, "INR",
                BankScenario.TIMEOUT_THEN_APPROVE);

        assertThat(BankRequest.fromFields(request.toFields())).isEqualTo(request);
        assertThat(request.bankKey()).isEqualTo(BankRequest.bankKey(PAYMENT_ID, "request-key-1"));
    }

    @Test
    void bankResponseRoundTripsWithAndWithoutDeclineReason() {
        BankResponse approved = new BankResponse("key", ATTEMPT_ID, PAYMENT_ID, BankOutcome.APPROVED, "BNK-1", null);
        BankResponse declined = new BankResponse(
                "key", ATTEMPT_ID, PAYMENT_ID, BankOutcome.DECLINED, "BNK-2", "insufficient_funds");

        assertThat(BankResponse.fromFields(approved.toFields())).isEqualTo(approved);
        assertThat(BankResponse.fromFields(declined.toFields())).isEqualTo(declined);
    }
}
