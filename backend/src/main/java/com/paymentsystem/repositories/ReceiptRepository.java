package com.paymentsystem.repositories;

import com.paymentsystem.models.Receipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByPaymentId(UUID paymentId);

    Optional<Receipt> findByPaymentIdAndPaymentMerchantId(UUID paymentId, UUID merchantId);

    Optional<Receipt> findByAttemptId(UUID attemptId);
}
