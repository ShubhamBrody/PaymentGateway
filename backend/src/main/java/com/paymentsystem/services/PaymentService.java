package com.paymentsystem.services;

import com.paymentsystem.dtos.CreatePaymentRequest;
import com.paymentsystem.dtos.PageResponse;
import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.PaymentResponse;
import com.paymentsystem.exceptions.ConflictException;
import com.paymentsystem.exceptions.InvalidRequestException;
import com.paymentsystem.exceptions.ResourceNotFoundException;
import com.paymentsystem.models.Merchant;
import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.models.Receipt;
import com.paymentsystem.repositories.MerchantRepository;
import com.paymentsystem.repositories.PaymentAttemptRepository;
import com.paymentsystem.repositories.PaymentRepository;
import com.paymentsystem.repositories.ReceiptRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String SUPPORTED_CURRENCY = "INR";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final MerchantRepository merchantRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ReceiptRepository receiptRepository;

    @Transactional
    public PaymentResponse createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        if (!SUPPORTED_CURRENCY.equals(request.currency())) {
            throw new InvalidRequestException("Only INR payments are supported");
        }
        Optional<Payment> existing = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return PaymentResponse.from(requireSameRequest(existing.get(), request));
        }
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found"));
        Payment payment = new Payment(
                merchant,
                request.amount(),
                request.currency(),
                request.description(),
                request.orderReference(),
                idempotencyKey);
        return PaymentResponse.from(paymentRepository.saveAndFlush(payment));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listPayments(UUID merchantId, PaymentStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, NEWEST_FIRST);
        Page<PaymentResponse> payments = (status == null
                ? paymentRepository.findByMerchantId(merchantId, pageRequest)
                : paymentRepository.findByMerchantIdAndStatus(merchantId, status, pageRequest))
                .map(PaymentResponse::from);
        return new PageResponse<>(
                payments.getContent(),
                payments.getNumber(),
                payments.getSize(),
                payments.getTotalElements(),
                payments.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID merchantId, UUID paymentId) {
        return PaymentResponse.from(findOwnedPayment(merchantId, paymentId));
    }

    @Transactional
    public PaymentResponse cancelPayment(UUID merchantId, UUID paymentId) {
        Payment payment = paymentRepository.findForUpdateByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(PaymentService::paymentNotFound);
        switch (payment.getStatus()) {
            case AWAITING_PAYMENT -> {
                payment.setStatus(PaymentStatus.CANCELLED);
                paymentRepository.flush();
            }
            // Cancelling twice returns the same result, so client retries are safe.
            case CANCELLED -> { }
            case PROCESSING -> throw new ConflictException("A payment with a pending attempt cannot be cancelled");
            case SUCCEEDED -> throw new ConflictException("A succeeded payment cannot be cancelled");
        }
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentAttemptResponse> listAttempts(UUID merchantId, UUID paymentId) {
        findOwnedPayment(merchantId, paymentId);
        String receiptNumber = receiptRepository.findByPaymentId(paymentId)
                .map(Receipt::getReceiptNumber)
                .orElse(null);
        return paymentAttemptRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(attempt -> PaymentAttemptResponse.from(
                        attempt, attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED ? receiptNumber : null))
                .toList();
    }

    private Payment findOwnedPayment(UUID merchantId, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(PaymentService::paymentNotFound);
    }

    private static Payment requireSameRequest(Payment payment, CreatePaymentRequest request) {
        boolean sameRequest = payment.getAmount() == request.amount()
                && payment.getCurrency().equals(request.currency())
                && Objects.equals(payment.getDescription(), request.description())
                && Objects.equals(payment.getOrderReference(), request.orderReference());
        if (!sameRequest) {
            throw new ConflictException("This Idempotency-Key was already used with different payment details");
        }
        return payment;
    }

    private static ResourceNotFoundException paymentNotFound() {
        return new ResourceNotFoundException("Payment not found");
    }
}
