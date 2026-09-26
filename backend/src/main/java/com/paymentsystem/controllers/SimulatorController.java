package com.paymentsystem.controllers;

import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.SimulatedAttemptRequest;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.security.CurrentMerchantId;
import com.paymentsystem.services.SimulatorService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulator/payments/{paymentId}/attempts")
@RequiredArgsConstructor
public class SimulatorController {

    private final SimulatorService simulatorService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentAttemptResponse simulateAttempt(
            @CurrentMerchantId UUID merchantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody SimulatedAttemptRequest request) {
        return simulatorService.simulateAttempt(merchantId, paymentId, request);
    }

    @PostMapping("/{attemptId}/succeed")
    public PaymentAttemptResponse succeedPendingAttempt(
            @CurrentMerchantId UUID merchantId, @PathVariable UUID paymentId, @PathVariable UUID attemptId) {
        return simulatorService.resolvePendingAttempt(
                merchantId, paymentId, attemptId, PaymentAttemptStatus.SUCCEEDED);
    }

    @PostMapping("/{attemptId}/decline")
    public PaymentAttemptResponse declinePendingAttempt(
            @CurrentMerchantId UUID merchantId, @PathVariable UUID paymentId, @PathVariable UUID attemptId) {
        return simulatorService.resolvePendingAttempt(
                merchantId, paymentId, attemptId, PaymentAttemptStatus.DECLINED);
    }
}
