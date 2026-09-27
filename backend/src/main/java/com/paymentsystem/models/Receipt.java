package com.paymentsystem.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

// One receipt per payment: the one-to-one join columns are unique, so a payment cannot be confirmed twice.
@Entity
@Table(
        name = "receipts",
        uniqueConstraints = @UniqueConstraint(name = "uk_receipts_receipt_number", columnNames = "receipt_number"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Receipt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "receipt_number", nullable = false, length = 20, updatable = false)
    private String receiptNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, unique = true, updatable = false)
    private Payment payment;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false, unique = true, updatable = false)
    private PaymentAttempt attempt;

    // Copied from the payment so the receipt stays an unchanging record of what was paid.
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 64, updatable = false)
    private String bankReference;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    public Receipt(Payment payment, PaymentAttempt attempt, String bankReference) {
        this.receiptNumber = newReceiptNumber();
        this.payment = payment;
        this.attempt = attempt;
        this.amount = payment.getAmount();
        this.currency = payment.getCurrency();
        this.bankReference = bankReference;
    }

    private static String newReceiptNumber() {
        byte[] random = new byte[6];
        ThreadLocalRandom.current().nextBytes(random);
        return "RCPT-" + HexFormat.of().withUpperCase().formatHex(random);
    }
}
