package com.paymentsystem.repositories;

import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // Use instead of findById for merchant requests: another merchant's payment comes back empty.
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    // Holds a row lock until the transaction ends, so status changes to one payment run one at a time.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findForUpdateByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    Page<Payment> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<Payment> findByMerchantIdAndStatus(UUID merchantId, PaymentStatus status, Pageable pageable);
}
