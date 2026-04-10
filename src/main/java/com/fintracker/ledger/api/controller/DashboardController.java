package com.fintracker.ledger.api.controller;

import com.fintracker.ledger.api.dto.MarkBillPaidRequest;
import com.fintracker.ledger.domain.model.DashboardSummary;
import com.fintracker.ledger.domain.model.UpcomingBill;
import com.fintracker.ledger.domain.service.DashboardService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Inbound REST Adapter: Dashboard Module.
 */
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

    @GetMapping("/bills")
    public ResponseEntity<List<UpcomingBill>> getBills(@RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(dashboardService.getUpcomingBills(userId));
    }

    @PostMapping("/bills/{id}/pay")
    public ResponseEntity<Void> markBillAsPaid(
            @PathVariable UUID id,
            @Valid @RequestBody MarkBillPaidRequest request
    ) {
        dashboardService.markBillAsPaid(id, request.transactionId());
        return ResponseEntity.noContent().build();
    }
}
