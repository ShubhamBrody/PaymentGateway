package com.paymentsystem.services;

import com.paymentsystem.bank.BankGateway;
import com.paymentsystem.bank.BankOutcome;
import com.paymentsystem.bank.BankRequest;
import com.paymentsystem.bank.BankResponse;
import com.paymentsystem.config.ProcessingProperties;
import com.paymentsystem.idempotency.PaymentRequestStatus;
import com.paymentsystem.idempotency.PaymentRequestStatusStore;
import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.models.Receipt;
import com.paymentsystem.queue.PaymentQueue;
import com.paymentsystem.queue.PaymentRequestMessage;
import com.paymentsystem.queue.Queues;
import com.paymentsystem.repositories.AttemptRef;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Worker-side state changes. Each step locks the payment row, checks the current status, and commits before
// any message leaves the service, so a redelivered message can always be handled a second time safely.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessor {

    private static final int RECOVERY_BATCH = 200;

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReceiptRepository receiptRepository;
    private final BankGateway bankGateway;
    private final PaymentQueue paymentQueue;
    private final PaymentRequestStatusStore statusStore;
    private final TransactionTemplate transactionTemplate;
    private final ProcessingProperties properties;

    private record Step(PaymentRequestStatus status, BankRequest bankRequest, BankResponse lateApproval) {

        static final Step NONE = new Step(null, null, null);

        Step(PaymentRequestStatus status, BankRequest bankRequest) {
            this(status, bankRequest, null);
        }
    }

    public void processPaymentRequest(PaymentRequestMessage message) {
        Step step = transactionTemplate.execute(tx -> {
            PaymentAttempt attempt = lockAttempt(message.paymentId(), message.attemptId());
            if (attempt == null) {
                log.warn("Dropping payment request for unknown attempt {}", message.attemptId());
                return Step.NONE;
            }
            if (attempt.getStatus() != PaymentAttemptStatus.QUEUED) {
                return new Step(PaymentRequestStatus.of(attempt, receiptNumber(attempt)), null);
            }
            markSentToBank(attempt);
            attemptRepository.flush();
            return new Step(PaymentRequestStatus.of(attempt, null), BankRequest.from(attempt));
        });
        finish(step);
    }

    public void processBankResponse(BankResponse response) {
        Step step = transactionTemplate.execute(tx -> {
            PaymentAttempt attempt = lockAttempt(response.paymentId(), response.attemptId());
            if (attempt == null) {
                log.warn("Dropping bank response for unknown attempt {}", response.attemptId());
                return Step.NONE;
            }
            if (attempt.getStatus().isFinal()) {
                // The bank approved after we gave up on it: keep the reply for reconciliation instead of dropping it.
                boolean lateApproval = attempt.getStatus() == PaymentAttemptStatus.FAILED
                        && response.outcome() == BankOutcome.APPROVED;
                return new Step(
                        PaymentRequestStatus.of(attempt, receiptNumber(attempt)), null, lateApproval ? response : null);
            }
            return new Step(applyBankResponse(attempt, response), null);
        });
        finish(step);
    }

    public int requeueStaleQueuedAttempts() {
        Instant cutoff = Instant.now().minus(properties.requeueAfter());
        List<AttemptRef> stale = attemptRepository.findQueuedBefore(cutoff, PageRequest.ofSize(RECOVERY_BATCH));
        int requeued = 0;
        for (AttemptRef ref : stale) {
            Boolean stillQueued = transactionTemplate.execute(tx -> {
                PaymentAttempt attempt = lockAttempt(ref.paymentId(), ref.attemptId());
                if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.QUEUED
                        || !attempt.getLastQueuedAt().isBefore(cutoff)) {
                    return false;
                }
                attempt.setLastQueuedAt(Instant.now());
                return true;
            });
            if (Boolean.TRUE.equals(stillQueued)) {
                paymentQueue.publishPaymentRequest(
                        new PaymentRequestMessage(ref.attemptId(), ref.paymentId(), ref.idempotencyKey()));
                requeued++;
            }
        }
        return requeued;
    }

    public int retryStalePendingAttempts() {
        Instant cutoff = Instant.now().minus(properties.bankReplyTimeout());
        List<AttemptRef> stale = attemptRepository.findPendingSentBefore(cutoff, PageRequest.ofSize(RECOVERY_BATCH));
        int retried = 0;
        for (AttemptRef ref : stale) {
            Step step = transactionTemplate.execute(tx -> {
                PaymentAttempt attempt = lockAttempt(ref.paymentId(), ref.attemptId());
                if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.PENDING
                        || !attempt.getLastSentToBankAt().isBefore(cutoff)) {
                    return Step.NONE;
                }
                if (attempt.getBankRequestCount() >= properties.maxBankRequests()) {
                    markFailed(attempt);
                    attemptRepository.flush();
                    return new Step(PaymentRequestStatus.of(attempt, null), null);
                }
                markSentToBank(attempt);
                attemptRepository.flush();
                return new Step(PaymentRequestStatus.of(attempt, null), BankRequest.from(attempt));
            });
            if (step.bankRequest() != null) {
                retried++;
            }
            finish(step);
        }
        return retried;
    }

    private PaymentAttempt lockAttempt(UUID paymentId, UUID attemptId) {
        if (paymentRepository.findForUpdateById(paymentId).isEmpty()) {
            return null;
        }
        return attemptRepository.findByIdAndPaymentId(attemptId, paymentId).orElse(null);
    }

    private PaymentRequestStatus applyBankResponse(PaymentAttempt attempt, BankResponse response) {
        Payment payment = attempt.getPayment();
        attempt.setBankReference(response.bankReference());
        attempt.setResolvedAt(Instant.now());
        String receiptNumber = null;
        if (response.outcome() == BankOutcome.APPROVED) {
            attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
            payment.setStatus(PaymentStatus.SUCCEEDED);
            receiptNumber = receiptRepository.save(new Receipt(payment, attempt, response.bankReference()))
                    .getReceiptNumber();
        } else {
            attempt.setStatus(PaymentAttemptStatus.DECLINED);
            attempt.setDeclineReason(response.declineReason());
            payment.setStatus(PaymentStatus.AWAITING_PAYMENT);
        }
        attemptRepository.flush();
        return PaymentRequestStatus.of(attempt, receiptNumber);
    }

    private void finish(Step step) {
        if (step.bankRequest() != null) {
            bankGateway.send(step.bankRequest());
        }
        if (step.lateApproval() != null) {
            paymentQueue.publishDeadLetter(
                    Queues.BANK_RESPONSES, step.lateApproval().toFields(), "approved_after_attempt_failed");
        }
        if (step.status() != null) {
            statusStore.save(step.status());
        }
    }

    private String receiptNumber(PaymentAttempt attempt) {
        if (attempt.getStatus() != PaymentAttemptStatus.SUCCEEDED) {
            return null;
        }
        return receiptRepository.findByAttemptId(attempt.getId()).map(Receipt::getReceiptNumber).orElse(null);
    }

    private static void markSentToBank(PaymentAttempt attempt) {
        attempt.setStatus(PaymentAttemptStatus.PENDING);
        attempt.setBankRequestCount(attempt.getBankRequestCount() + 1);
        attempt.setLastSentToBankAt(Instant.now());
    }

    private static void markFailed(PaymentAttempt attempt) {
        attempt.setStatus(PaymentAttemptStatus.FAILED);
        attempt.setDeclineReason("bank_timeout");
        attempt.setResolvedAt(Instant.now());
        attempt.getPayment().setStatus(PaymentStatus.AWAITING_PAYMENT);
    }
}
