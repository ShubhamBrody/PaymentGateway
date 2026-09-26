package com.paymentsystem.dtos;

import com.paymentsystem.models.Merchant;
import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(UUID id, String businessName, String email, Instant createdAt) {

    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId(), merchant.getBusinessName(), merchant.getEmail(), merchant.getCreatedAt());
    }
}
