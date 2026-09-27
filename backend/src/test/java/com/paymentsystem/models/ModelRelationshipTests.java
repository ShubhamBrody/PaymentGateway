package com.paymentsystem.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentsystem.dtos.MerchantResponse;
import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.PaymentResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModelRelationshipTests {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void paymentCascadesAttemptsAndReloadsRelationships() {
        Merchant merchant = persistMerchant("merchant@example.com");
        Payment payment = new Payment(merchant, 1000, "INR", "Test order", "order-123", "key-1");
        PaymentAttempt declined = payment.addAttempt("request-key-1", BankScenario.DECLINE);
        declined.setStatus(PaymentAttemptStatus.DECLINED);
        declined.setDeclineReason("insufficient_funds");
        payment.addAttempt("request-key-2", BankScenario.APPROVE);
        entityManager.persist(payment);
        entityManager.flush();
        entityManager.clear();

        Payment stored = entityManager.find(Payment.class, payment.getId());
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.AWAITING_PAYMENT);
        assertThat(stored.getMerchant().getId()).isEqualTo(merchant.getId());
        assertThat(stored.getAttempts())
                .extracting(PaymentAttempt::getStatus)
                .containsExactly(PaymentAttemptStatus.DECLINED, PaymentAttemptStatus.QUEUED);
        assertThat(stored.getAttempts()).allSatisfy(attempt -> assertThat(attempt.getPayment()).isSameAs(stored));
    }

    @Test
    void entitiesMapToResponsesWithoutSecrets() {
        Merchant merchant = persistMerchant("merchant@example.com");
        Payment payment = new Payment(merchant, 2500, "INR", null, null, "key-1");
        PaymentAttempt attempt = payment.addAttempt("request-key-1", BankScenario.APPROVE);
        entityManager.persist(payment);
        entityManager.flush();

        assertThat(MerchantResponse.from(merchant))
                .isEqualTo(new MerchantResponse(
                        merchant.getId(), "Demo Store", "merchant@example.com", merchant.getCreatedAt()));
        assertThat(PaymentResponse.from(payment).amount()).isEqualTo(2500);
        PaymentAttemptResponse response = PaymentAttemptResponse.from(attempt, null);
        assertThat(response.paymentId()).isEqualTo(payment.getId());
        assertThat(response.idempotencyKey()).isEqualTo("request-key-1");
        assertThat(response.status()).isEqualTo(PaymentAttemptStatus.QUEUED);
    }

    @Test
    void requestKeyIsUniqueWithinAPayment() {
        Merchant merchant = persistMerchant("merchant@example.com");
        Payment first = new Payment(merchant, 1000, "INR", null, null, "key-1");
        Payment second = new Payment(merchant, 1000, "INR", null, null, "key-2");
        first.addAttempt("shared-request-key", BankScenario.APPROVE);
        second.addAttempt("shared-request-key", BankScenario.APPROVE);
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();

        first.addAttempt("shared-request-key", BankScenario.APPROVE);
        assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void aPaymentCanHaveOnlyOneReceipt() {
        Merchant merchant = persistMerchant("merchant@example.com");
        Payment payment = new Payment(merchant, 1000, "INR", null, null, "key-1");
        PaymentAttempt firstAttempt = payment.addAttempt("request-key-1", BankScenario.APPROVE);
        PaymentAttempt secondAttempt = payment.addAttempt("request-key-2", BankScenario.APPROVE);
        entityManager.persist(payment);
        Receipt receipt = new Receipt(payment, firstAttempt, "BNK-1");
        entityManager.persist(receipt);
        entityManager.flush();

        assertThat(receipt.getReceiptNumber()).matches("RCPT-[0-9A-F]{12}");
        assertThat(receipt.getAmount()).isEqualTo(1000);
        entityManager.persist(new Receipt(payment, secondAttempt, "BNK-2"));
        assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void idempotencyKeyIsUniquePerMerchant() {
        Merchant first = persistMerchant("first@example.com");
        Merchant second = persistMerchant("second@example.com");
        entityManager.persist(new Payment(first, 1000, "INR", null, null, "shared-key"));
        entityManager.persist(new Payment(second, 1000, "INR", null, null, "shared-key"));
        entityManager.flush();

        entityManager.persist(new Payment(first, 1000, "INR", null, null, "shared-key"));
        assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void merchantEmailIsUnique() {
        persistMerchant("merchant@example.com");
        entityManager.persist(new Merchant("Other Store", "merchant@example.com", "hash"));
        assertThatThrownBy(entityManager::flush).isInstanceOf(PersistenceException.class);
    }

    private Merchant persistMerchant(String email) {
        Merchant merchant = new Merchant("Demo Store", email, "hash");
        entityManager.persist(merchant);
        return merchant;
    }
}
