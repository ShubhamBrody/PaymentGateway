package com.paymentsystem.repositories;

import com.paymentsystem.models.Merchant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    // Emails are matched exactly, so services must normalize them to lowercase before saving or searching.
    Optional<Merchant> findByEmail(String email);

    boolean existsByEmail(String email);
}
