package com.paymentsystem.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paymentsystem.dtos.CreatePaymentAttemptRequest;
import com.paymentsystem.exceptions.ConflictException;
import com.paymentsystem.exceptions.ResourceNotFoundException;
import com.paymentsystem.idempotency.PaymentRequestStatus;
import com.paymentsystem.idempotency.PaymentRequestStatusStore;
import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.Merchant;
import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.queue.PaymentQueue;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import com.paymentsystem.support.RecordingTransactionManager;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTests {

    private static final String REQUEST_KEY = "request-key-1";
    private static final CreatePaymentAttemptRequest APPROVE = new CreatePaymentAttemptRequest(BankScenario.APPROVE);

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentAttemptRepository attemptRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private PaymentQueue paymentQueue;

    @Mock
    private PaymentRequestStatusStore statusStore;

    private final List<String> events = new ArrayList<>();
    private PaymentRequestService service;
    private Merchant merchant;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = new PaymentRequestService(
                paymentRepository, attemptRepository, receiptRepository, paymentQueue, statusStore,
                new TransactionTemplate(new RecordingTransactionManager(events)));
        merchant = withId(new Merchant("Demo Store", "merchant@example.com", "hash"));
        payment = withId(new Payment(merchant, 1000, "INR", null, null, "payment-key"));
        lenient().when(paymentRepository.findForUpdateByIdAndMerchantId(payment.getId(), merchant.getId()))
                .thenReturn(Optional.of(payment));
        lenient().when(attemptRepository.saveAndFlush(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @Test
    void newRequestIsStoredAsQueuedThenPublishedAfterCommit() {
        doAnswer(invocation -> events.add("queue")).when(paymentQueue).publishPaymentRequest(any());

        PaymentRequestService.Submission submission = submit(APPROVE);

        assertThat(submission.replayed()).isFalse();
        assertThat(submission.status().status()).isEqualTo(PaymentAttemptStatus.QUEUED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(events).containsExactly("commit", "queue");
        verify(statusStore).save(any());
    }

    @Test
    void retryWithTheSameKeyIsAnsweredFromRedisWithoutTouchingTheDatabase() {
        when(statusStore.find(payment.getId(), REQUEST_KEY)).thenReturn(Optional.of(cached(merchant.getId())));

        PaymentRequestService.Submission submission = submit(APPROVE);

        assertThat(submission.replayed()).isTrue();
        assertThat(submission.status().source()).isEqualTo("cache");
        verifyNoInteractions(paymentRepository, attemptRepository, paymentQueue);
    }

    @Test
    void sameKeyWithADifferentScenarioIsAConflict() {
        when(statusStore.find(payment.getId(), REQUEST_KEY)).thenReturn(Optional.of(cached(merchant.getId())));

        assertThatThrownBy(() -> submit(new CreatePaymentAttemptRequest(BankScenario.DECLINE)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void keyBelongingToAnotherMerchantIsReportedAsNotFound() {
        when(statusStore.find(payment.getId(), REQUEST_KEY)).thenReturn(Optional.of(cached(UUID.randomUUID())));

        assertThatThrownBy(() -> submit(APPROVE)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void queueOutageAfterCommitStillAcceptsTheRequest() {
        doThrow(new RedisConnectionFailureException("down")).when(paymentQueue).publishPaymentRequest(any());

        PaymentRequestService.Submission submission = submit(APPROVE);

        assertThat(submission.replayed()).isFalse();
        assertThat(submission.status().status()).isEqualTo(PaymentAttemptStatus.QUEUED);
    }

    @Test
    void cacheOutageFallsBackToTheDatabaseCopy() {
        when(statusStore.find(payment.getId(), REQUEST_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        PaymentAttempt existing = withId(payment.addAttempt(REQUEST_KEY, BankScenario.APPROVE));
        when(attemptRepository.findByPaymentIdAndIdempotencyKey(payment.getId(), REQUEST_KEY))
                .thenReturn(Optional.of(existing));

        PaymentRequestService.Submission submission = submit(APPROVE);

        assertThat(submission.replayed()).isTrue();
        assertThat(submission.status().attemptId()).isEqualTo(existing.getId());
        assertThat(submission.status().source()).isEqualTo("database");
        verify(paymentQueue, never()).publishPaymentRequest(any());
    }

    @Test
    void paymentWithARequestInProgressRejectsANewKey() {
        payment.setStatus(PaymentStatus.PROCESSING);

        assertThatThrownBy(() -> submit(APPROVE)).isInstanceOf(ConflictException.class);
        verify(attemptRepository, never()).saveAndFlush(any());
        verify(paymentQueue, never()).publishPaymentRequest(any());
    }

    private PaymentRequestService.Submission submit(CreatePaymentAttemptRequest request) {
        return service.submit(merchant.getId(), payment.getId(), REQUEST_KEY, request);
    }

    private PaymentRequestStatus cached(UUID merchantId) {
        return new PaymentRequestStatus(
                UUID.randomUUID(), payment.getId(), merchantId, REQUEST_KEY, BankScenario.APPROVE,
                PaymentAttemptStatus.PENDING, null, 1, null, 1, Instant.now());
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
