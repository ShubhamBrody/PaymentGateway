package com.paymentsystem.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "merchants", uniqueConstraints = @UniqueConstraint(name = "uk_merchants_email", columnNames = "email"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Setter
    @Column(nullable = false, length = 100)
    private String businessName;

    @Column(nullable = false, length = 254)
    private String email;

    @Setter
    @Column(nullable = false)
    private String passwordHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Merchant(String businessName, String email, String passwordHash) {
        this.businessName = businessName;
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
