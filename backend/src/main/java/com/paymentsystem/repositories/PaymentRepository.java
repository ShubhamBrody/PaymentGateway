package com.paymentsystem.repositories;

import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // Use instead of findById for merchant requests: another merchant's payment comes back empty.
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    // Holds a row lock until the transaction ends, so status changes to one payment run one at a time.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findForUpdateByIdAndMerchantId(UUID id, UUID merchantId);

    // Queue workers act for the system rather than a merchant, so their lock is not merchant-scoped.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findForUpdateById(UUID id);

    @Query("select p.status, count(p) from Payment p group by p.status")
    List<Object[]> countByStatus();

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    Page<Payment> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<Payment> findByMerchantIdAndStatus(UUID merchantId, PaymentStatus status, Pageable pageable);
}
