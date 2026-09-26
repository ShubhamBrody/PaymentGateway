package com.paymentsystem.services;

import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.SimulatedAttemptRequest;
import com.paymentsystem.exceptions.ConflictException;
import com.paymentsystem.exceptions.ResourceNotFoundException;
import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SimulatorService {

    private static final String SIMULATED_DECLINE_REASON = "simulated_decline";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional
    public PaymentAttemptResponse simulateAttempt(UUID merchantId, UUID paymentId, SimulatedAttemptRequest request) {
        Payment payment = lockPayment(merchantId, paymentId);
        String conflict = switch (payment.getStatus()) {
            case AWAITING_PAYMENT -> null;
            case PROCESSING -> "Resolve the pending attempt before making another attempt";
            case SUCCEEDED -> "The payment has already succeeded";
            case CANCELLED -> "A cancelled payment cannot accept attempts";
        };
        if (conflict != null) {
            throw new ConflictException(conflict);
        }
        PaymentAttempt attempt = payment.addAttempt(request.outcome());
        recordResult(payment, attempt, request.outcome());
        return PaymentAttemptResponse.from(paymentAttemptRepository.saveAndFlush(attempt));
    }

    @Transactional
    public PaymentAttemptResponse resolvePendingAttempt(
            UUID merchantId, UUID paymentId, UUID attemptId, PaymentAttemptStatus result) {
        if (result == PaymentAttemptStatus.PENDING) {
            throw new IllegalArgumentException("A pending attempt must resolve to SUCCEEDED or DECLINED");
        }
        Payment payment = lockPayment(merchantId, paymentId);
        PaymentAttempt attempt = paymentAttemptRepository
                .findByIdAndPaymentIdAndPaymentMerchantId(attemptId, paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found"));
        if (attempt.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new ConflictException("Only a pending attempt can be resolved");
        }
        recordResult(payment, attempt, result);
        paymentAttemptRepository.flush();
        return PaymentAttemptResponse.from(attempt);
    }

    private Payment lockPayment(UUID merchantId, UUID paymentId) {
        return paymentRepository.findForUpdateByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    private static void recordResult(Payment payment, PaymentAttempt attempt, PaymentAttemptStatus result) {
        attempt.setStatus(result);
        switch (result) {
            case PENDING -> payment.setStatus(PaymentStatus.PROCESSING);
            case SUCCEEDED -> {
                attempt.setResolvedAt(Instant.now());
                payment.setStatus(PaymentStatus.SUCCEEDED);
            }
            case DECLINED -> {
                attempt.setResolvedAt(Instant.now());
                attempt.setDeclineReason(SIMULATED_DECLINE_REASON);
                payment.setStatus(PaymentStatus.AWAITING_PAYMENT);
            }
        }
    }
}
