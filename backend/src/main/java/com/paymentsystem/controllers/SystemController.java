package com.paymentsystem.controllers;

import com.paymentsystem.dtos.SystemStatusResponse;
import com.paymentsystem.services.SystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return systemService.getStatus();
    }
}