package com.paymentsystem.services;

import com.paymentsystem.dtos.MerchantResponse;
import com.paymentsystem.exceptions.ResourceNotFoundException;
import com.paymentsystem.repositories.MerchantRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;

    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .map(MerchantResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found"));
    }
}
