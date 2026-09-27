package com.paymentsystem.controllers;

import com.paymentsystem.dtos.SystemStatsResponse;
import com.paymentsystem.services.SystemStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final SystemStatsService systemStatsService;

    @GetMapping("/stats")
    public SystemStatsResponse stats() {
        return systemStatsService.stats();
    }
}
