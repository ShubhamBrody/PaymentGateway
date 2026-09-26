package com.paymentsystem.repositories;

import com.paymentsystem.models.PaymentAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    // Call only after confirming the payment belongs to the signed-in merchant.
    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Optional<PaymentAttempt> findByIdAndPaymentIdAndPaymentMerchantId(UUID id, UUID paymentId, UUID merchantId);
}
