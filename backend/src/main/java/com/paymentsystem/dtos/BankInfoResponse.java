package com.paymentsystem.dtos;

import com.paymentsystem.bank.BankDirectory;
import java.time.Duration;

// approveDelayMillis is the bank's typical reply time for an approval, before random jitter.
public record BankInfoResponse(String code, String name, long approveDelayMillis, double declineRate) {

    public static BankInfoResponse from(BankDirectory.Bank bank, Duration baseApproveDelay) {
        return new BankInfoResponse(
                bank.code(), bank.name(), bank.scaled(baseApproveDelay).toMillis(), bank.declineRate());
    }
}
