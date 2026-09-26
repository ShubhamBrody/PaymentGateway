package com.paymentsystem.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsystem.models.Merchant;
import com.paymentsystem.models.Payment;
import com.paymentsystem.models.PaymentAttempt;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryTests {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    private Merchant merchant;
    private Merchant otherMerchant;

    @BeforeEach
    void createMerchants() {
        merchant = merchantRepository.save(new Merchant("Demo Store", "merchant@example.com", "hash"));
        otherMerchant = merchantRepository.save(new Merchant("Other Store", "other@example.com", "hash"));
    }

    @Test
    void findsMerchantsByEmail() {
        assertThat(merchantRepository.findByEmail("merchant@example.com")).contains(merchant);
        assertThat(merchantRepository.existsByEmail("other@example.com")).isTrue();
        assertThat(merchantRepository.existsByEmail("missing@example.com")).isFalse();
    }

    @Test
    void paymentLookupsAreScopedToTheOwningMerchant() {
        Payment payment = paymentRepository.save(payment(merchant, "key-1"));

        assertThat(paymentRepository.findByIdAndMerchantId(payment.getId(), merchant.getId())).contains(payment);
        assertThat(paymentRepository.findByIdAndMerchantId(payment.getId(), otherMerchant.getId())).isEmpty();
        assertThat(paymentRepository.findByMerchantIdAndIdempotencyKey(merchant.getId(), "key-1")).contains(payment);
        assertThat(paymentRepository.findByMerchantIdAndIdempotencyKey(otherMerchant.getId(), "key-1")).isEmpty();
    }

    @Test
    void listsOnlyTheMerchantsPaymentsWithPagingAndStatusFilter() {
        for (int i = 0; i < 3; i++) {
            paymentRepository.save(payment(merchant, "key-" + i));
        }
        Payment cancelled = payment(merchant, "key-cancelled");
        cancelled.setStatus(PaymentStatus.CANCELLED);
        paymentRepository.save(cancelled);
        paymentRepository.save(payment(otherMerchant, "key-other"));

        Page<Payment> firstPage = paymentRepository.findByMerchantId(
                merchant.getId(), PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));
        assertThat(firstPage.getContent()).hasSize(2).allMatch(found -> found.getMerchant() == merchant);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);

        Page<Payment> cancelledPayments = paymentRepository.findByMerchantIdAndStatus(
                merchant.getId(), PaymentStatus.CANCELLED, PageRequest.of(0, 10));
        assertThat(cancelledPayments.getContent()).containsExactly(cancelled);
    }

    @Test
    void attemptLookupsFollowThePaymentAndMerchant() {
        Payment payment = payment(merchant, "key-1");
        PaymentAttempt declined = payment.addAttempt(PaymentAttemptStatus.DECLINED);
        paymentRepository.saveAndFlush(payment);
        PaymentAttempt pending = paymentAttemptRepository.saveAndFlush(payment.addAttempt(PaymentAttemptStatus.PENDING));

        assertThat(paymentAttemptRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()))
                .containsExactly(declined, pending);
        assertThat(paymentAttemptRepository.findByIdAndPaymentIdAndPaymentMerchantId(
                pending.getId(), payment.getId(), merchant.getId())).contains(pending);
        assertThat(paymentAttemptRepository.findByIdAndPaymentIdAndPaymentMerchantId(
                pending.getId(), payment.getId(), otherMerchant.getId())).isEmpty();
    }

    private static Payment payment(Merchant owner, String idempotencyKey) {
        return new Payment(owner, 1000, "INR", "Test order", null, idempotencyKey);
    }
}
