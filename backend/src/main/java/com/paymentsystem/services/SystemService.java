package com.paymentsystem.services;

import com.paymentsystem.dtos.SystemStatusResponse;
import org.springframework.stereotype.Service;

@Service
public class SystemService {

    public SystemStatusResponse getStatus() {
        return new SystemStatusResponse("payment-system", "simulation");
    }
}