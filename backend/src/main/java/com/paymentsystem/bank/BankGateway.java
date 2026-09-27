package com.paymentsystem.bank;

import com.paymentsystem.queue.PaymentQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Boundary to the external bank: a real integration would make an HTTP call here. Replies arrive later on a queue.
@Component
@RequiredArgsConstructor
public class BankGateway {

    private final PaymentQueue paymentQueue;

    public void send(BankRequest request) {
        paymentQueue.publishBankRequest(request);
    }
}
