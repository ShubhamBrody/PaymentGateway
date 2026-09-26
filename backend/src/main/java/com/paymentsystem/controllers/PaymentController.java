package com.paymentsystem.controllers;

import com.paymentsystem.dtos.CreatePaymentRequest;
import com.paymentsystem.dtos.PageResponse;
import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.PaymentResponse;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.security.CurrentMerchantId;
import com.paymentsystem.services.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @CurrentMerchantId UUID merchantId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse payment = paymentService.createPayment(merchantId, idempotencyKey, request);
        URI location = UriComponentsBuilder.fromPath("/api/v1/payments/{paymentId}")
                .buildAndExpand(payment.id())
                .toUri();
        return ResponseEntity.created(location).body(payment);
    }

    @GetMapping
    public PageResponse<PaymentResponse> listPayments(
            @CurrentMerchantId UUID merchantId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return paymentService.listPayments(merchantId, status, page, size);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@CurrentMerchantId UUID merchantId, @PathVariable UUID paymentId) {
        return paymentService.getPayment(merchantId, paymentId);
    }

    @PostMapping("/{paymentId}/cancel")
    public PaymentResponse cancelPayment(@CurrentMerchantId UUID merchantId, @PathVariable UUID paymentId) {
        return paymentService.cancelPayment(merchantId, paymentId);
    }

    @GetMapping("/{paymentId}/attempts")
    public List<PaymentAttemptResponse> listAttempts(
            @CurrentMerchantId UUID merchantId, @PathVariable UUID paymentId) {
        return paymentService.listAttempts(merchantId, paymentId);
    }
}
