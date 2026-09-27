package com.paymentsystem.controllers;

import com.paymentsystem.bank.BankDirectory;
import com.paymentsystem.config.BankSimulatorProperties;
import com.paymentsystem.dtos.BankInfoResponse;
import com.paymentsystem.dtos.SystemStatsResponse;
import com.paymentsystem.services.SystemStatsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final SystemStatsService systemStatsService;
    private final BankDirectory bankDirectory;
    private final BankSimulatorProperties bankProperties;

    @GetMapping("/stats")
    public SystemStatsResponse stats() {
        return systemStatsService.stats();
    }

    @GetMapping("/banks")
    public List<BankInfoResponse> banks() {
        return bankDirectory.all().stream()
                .map(bank -> BankInfoResponse.from(bank, bankProperties.approveDelay()))
                .toList();
    }
}
