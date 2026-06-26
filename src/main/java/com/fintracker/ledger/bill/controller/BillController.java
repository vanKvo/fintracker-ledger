package com.fintracker.ledger.bill.controller;

import com.fintracker.ledger.bill.dto.MarkBillPaidRequest;
import com.fintracker.ledger.bill.dto.UpcomingBillDto;
import com.fintracker.ledger.bill.service.BillService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class BillController {

    private final BillService billService;

    public BillController(BillService billService) {
        this.billService = billService;
    }

    @GetMapping("/bills")
    public ResponseEntity<List<UpcomingBillDto>> getBills(@RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(billService.getUpcomingBills(userId));
    }

    @PostMapping("/bills/{id}/pay")
    public ResponseEntity<Void> markBillAsPaid(
            @PathVariable UUID id,
            @Valid @RequestBody MarkBillPaidRequest request
    ) {
        billService.markBillAsPaid(id, request.transactionId());
        return ResponseEntity.noContent().build();
    }
}
