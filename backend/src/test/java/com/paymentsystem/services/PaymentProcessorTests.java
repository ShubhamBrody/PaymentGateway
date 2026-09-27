package com.paymentsystem.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paymentsystem.bank.BankGateway;
import com.paymentsystem.bank.BankOutcome;
import com.paymentsystem.bank.BankRequest;
import com.paymentsystem.bank.BankResponse;
import com.paymentsystem.config.ProcessingProperties;
import com.paymentsystem.idempotency.PaymentRequestStatusStore;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.Merchant;
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
import com.paymentsystem.support.RecordingTransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentProcessorTests {

    private static final String REQUEST_KEY = "request-key-1";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentAttemptRepository attemptRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private BankGateway bankGateway;

    @Mock
    private PaymentQueue paymentQueue;

    @Mock
    private PaymentRequestStatusStore statusStore;

    private final List<String> events = new ArrayList<>();
    private final ProcessingProperties properties = new ProcessingProperties(
            false, 1, 50, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(15), 5,
            Duration.ofSeconds(15), Duration.ofSeconds(8), 3, Duration.ofHours(24), Duration.ofSeconds(2),
            Duration.ofSeconds(5));

    private PaymentProcessor processor;
    private Payment payment;
    private PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        processor = new PaymentProcessor(
                paymentRepository, attemptRepository, receiptRepository, bankGateway, paymentQueue, statusStore,
                new TransactionTemplate(new RecordingTransactionManager(events)), properties);
        Merchant merchant = withId(new Merchant("Demo Store", "merchant@example.com", "hash"));
        payment = withId(new Payment(merchant, 1000, "INR", null, null, "payment-key"));
        payment.setStatus(PaymentStatus.PROCESSING);
        attempt = withId(payment.addAttempt(REQUEST_KEY, BankScenario.APPROVE, "BANK01"));
        attempt.setLastQueuedAt(Instant.now());
        lenient().when(paymentRepository.findForUpdateById(payment.getId())).thenReturn(Optional.of(payment));
        lenient().when(attemptRepository.findByIdAndPaymentId(attempt.getId(), payment.getId()))
                .thenReturn(Optional.of(attempt));
        lenient().doAnswer(invocation -> events.add("bank")).when(bankGateway).send(any());
    }

    @Test
    void queuedRequestIsMarkedPendingAndSentToTheBankOnlyAfterCommit() {
        processor.processPaymentRequest(message());

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getBankRequestCount()).isEqualTo(1);
        assertThat(attempt.getLastSentToBankAt()).isNotNull();
        assertThat(events).containsExactly("commit", "bank");
        verify(bankGateway).send(argThat(request ->
                request.bankKey().equals(BankRequest.bankKey(payment.getId(), REQUEST_KEY))));
        verify(statusStore).save(argThat(status -> status.status() == PaymentAttemptStatus.PENDING));
    }

    @Test
    void redeliveredRequestIsNotSentToTheBankAgain() {
        attempt.setStatus(PaymentAttemptStatus.PENDING);

        processor.processPaymentRequest(message());

        verify(bankGateway, never()).send(any());
        verify(statusStore).save(argThat(status -> status.status() == PaymentAttemptStatus.PENDING));
    }

    @Test
    void approvalCompletesThePaymentAndIssuesOneReceipt() {
        attempt.setStatus(PaymentAttemptStatus.PENDING);
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        processor.processBankResponse(response(BankOutcome.APPROVED));

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt.getBankReference()).isEqualTo("BNK-1");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(receiptRepository).save(argThat(receipt ->
                receipt.getAttempt() == attempt && receipt.getAmount() == 1000));
        verify(statusStore).save(argThat(status ->
                status.status() == PaymentAttemptStatus.SUCCEEDED && status.receiptNumber().startsWith("RCPT-")));
    }

    @Test
    void duplicateApprovalDoesNotIssueASecondReceipt() {
        attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
        when(receiptRepository.findByAttemptId(attempt.getId()))
                .thenReturn(Optional.of(new Receipt(payment, attempt, "BNK-1")));

        processor.processBankResponse(response(BankOutcome.APPROVED));

        verify(receiptRepository, never()).save(any());
        verify(statusStore).save(argThat(status -> status.receiptNumber() != null));
    }

    @Test
    void declineReturnsThePaymentToAwaitingPayment() {
        attempt.setStatus(PaymentAttemptStatus.PENDING);

        processor.processBankResponse(response(BankOutcome.DECLINED));

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.DECLINED);
        assertThat(attempt.getDeclineReason()).isEqualTo("insufficient_funds");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AWAITING_PAYMENT);
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void approvalArrivingAfterTheAttemptFailedIsKeptAsADeadLetter() {
        attempt.setStatus(PaymentAttemptStatus.FAILED);

        processor.processBankResponse(response(BankOutcome.APPROVED));

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        verify(receiptRepository, never()).save(any());
        verify(paymentQueue).publishDeadLetter(
                eq(Queues.BANK_RESPONSES), anyMap(), eq("approved_after_attempt_failed"));
    }

    @Test
    void stalePendingRequestIsResentWithTheSameIdempotencyKey() {
        markPendingSince(Instant.now().minusSeconds(60), 1);
        when(attemptRepository.findPendingSentBefore(any(), any())).thenReturn(List.of(ref()));

        int resent = processor.retryStalePendingAttempts();

        assertThat(resent).isEqualTo(1);
        assertThat(attempt.getBankRequestCount()).isEqualTo(2);
        assertThat(events).containsExactly("commit", "bank");
        verify(bankGateway).send(argThat(request ->
                request.bankKey().equals(BankRequest.bankKey(payment.getId(), REQUEST_KEY))));
    }

    @Test
    void requestFailsAfterTheLastBankRetryTimesOut() {
        markPendingSince(Instant.now().minusSeconds(60), properties.maxBankRequests());
        when(attemptRepository.findPendingSentBefore(any(), any())).thenReturn(List.of(ref()));

        int resent = processor.retryStalePendingAttempts();

        assertThat(resent).isZero();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getDeclineReason()).isEqualTo("bank_timeout");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AWAITING_PAYMENT);
        verify(bankGateway, never()).send(any());
    }

    @Test
    void recentlySentRequestIsNotResent() {
        markPendingSince(Instant.now(), 1);
        when(attemptRepository.findPendingSentBefore(any(), any())).thenReturn(List.of(ref()));

        assertThat(processor.retryStalePendingAttempts()).isZero();
        verify(bankGateway, never()).send(any());
    }

    @Test
    void queuedRequestWhoseMessageWasLostIsRequeuedWithTheSameKey() {
        attempt.setLastQueuedAt(Instant.now().minusSeconds(60));
        when(attemptRepository.findQueuedBefore(any(), any())).thenReturn(List.of(ref()));

        int requeued = processor.requeueStaleQueuedAttempts();

        assertThat(requeued).isEqualTo(1);
        verify(paymentQueue).publishPaymentRequest(message());
    }

    private void markPendingSince(Instant sentAt, int bankRequests) {
        attempt.setStatus(PaymentAttemptStatus.PENDING);
        attempt.setBankRequestCount(bankRequests);
        attempt.setLastSentToBankAt(sentAt);
    }

    private PaymentRequestMessage message() {
        return new PaymentRequestMessage(attempt.getId(), payment.getId(), REQUEST_KEY);
    }

    private AttemptRef ref() {
        return new AttemptRef(attempt.getId(), payment.getId(), REQUEST_KEY);
    }

    private BankResponse response(BankOutcome outcome) {
        return new BankResponse(
                BankRequest.bankKey(payment.getId(), REQUEST_KEY), attempt.getId(), payment.getId(), outcome, "BNK-1",
                outcome == BankOutcome.DECLINED ? "insufficient_funds" : null);
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
