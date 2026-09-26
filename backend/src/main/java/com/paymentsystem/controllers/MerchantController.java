package com.paymentsystem.controllers;

import com.paymentsystem.dtos.MerchantResponse;
import com.paymentsystem.security.CurrentMerchantId;
import com.paymentsystem.services.MerchantService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @GetMapping("/me")
    public MerchantResponse getCurrentMerchant(@CurrentMerchantId UUID merchantId) {
        return merchantService.getMerchant(merchantId);
    }
}
