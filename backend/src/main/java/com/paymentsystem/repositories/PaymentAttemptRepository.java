package com.paymentsystem.repositories;

import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    // Call only after confirming the payment belongs to the signed-in merchant.
    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Optional<PaymentAttempt> findByIdAndPaymentIdAndPaymentMerchantId(UUID id, UUID paymentId, UUID merchantId);

    Optional<PaymentAttempt> findByIdAndPaymentId(UUID id, UUID paymentId);

    Optional<PaymentAttempt> findByPaymentIdAndIdempotencyKey(UUID paymentId, String idempotencyKey);

    Optional<PaymentAttempt> findByPaymentIdAndIdempotencyKeyAndPaymentMerchantId(
            UUID paymentId, String idempotencyKey, UUID merchantId);

    @Query("""
            select new com.paymentsystem.repositories.AttemptRef(a.id, a.payment.id, a.idempotencyKey)
            from PaymentAttempt a
            where a.status = com.paymentsystem.models.PaymentAttemptStatus.QUEUED and a.lastQueuedAt < :cutoff
            order by a.lastQueuedAt
            """)
    List<AttemptRef> findQueuedBefore(@Param("cutoff") Instant cutoff, Pageable limit);

    @Query("""
            select new com.paymentsystem.repositories.AttemptRef(a.id, a.payment.id, a.idempotencyKey)
            from PaymentAttempt a
            where a.status = com.paymentsystem.models.PaymentAttemptStatus.PENDING and a.lastSentToBankAt < :cutoff
            order by a.lastSentToBankAt
            """)
    List<AttemptRef> findPendingSentBefore(@Param("cutoff") Instant cutoff, Pageable limit);

    @Query("select a.status, count(a) from PaymentAttempt a group by a.status")
    List<Object[]> countByStatus();

    @Query("select a.bankCode, a.status, count(a) from PaymentAttempt a group by a.bankCode, a.status")
    List<Object[]> countByBankAndStatus();

    long countByPaymentId(UUID paymentId);

    long countByStatus(PaymentAttemptStatus status);
}
