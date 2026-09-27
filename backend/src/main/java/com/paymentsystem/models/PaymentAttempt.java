package com.paymentsystem.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(
        name = "payment_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_attempts_payment_idempotency_key", columnNames = {"payment_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_payment_attempts_payment", columnList = "payment_id"),
                @Index(name = "idx_payment_attempts_status", columnList = "status")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private BankScenario scenario;

    // The customer's bank this attempt is sent to. The default only fills rows created before banks existed.
    @ColumnDefault("'BANK01'")
    @Column(name = "bank_code", nullable = false, length = 10, updatable = false)
    private String bankCode;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentAttemptStatus status;

    @Setter
    @Column(length = 100)
    private String declineReason;

    @Setter
    @Column(length = 64)
    private String bankReference;

    // Every send to the bank, including retries that reuse the same idempotency key.
    @Setter
    private int bankRequestCount;

    @Setter
    private Instant lastQueuedAt;

    @Setter
    private Instant lastSentToBankAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    private Instant resolvedAt;

    @Version
    private long version;

    // Created through Payment.addAttempt so both sides of the relationship stay in sync.
    PaymentAttempt(Payment payment, String idempotencyKey, BankScenario scenario, String bankCode) {
        this.payment = payment;
        this.idempotencyKey = idempotencyKey;
        this.scenario = scenario;
        this.bankCode = bankCode;
        this.status = PaymentAttemptStatus.QUEUED;
    }
}
