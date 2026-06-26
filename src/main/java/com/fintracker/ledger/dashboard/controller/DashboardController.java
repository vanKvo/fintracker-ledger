package com.fintracker.ledger.dashboard.controller;

import com.fintracker.ledger.dashboard.model.DashboardSummary;
import com.fintracker.ledger.dashboard.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard/aggregations")
    public ResponseEntity<DashboardSummary> getAggregations(@RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(dashboardService.getAggregations(userId));
    }
}
