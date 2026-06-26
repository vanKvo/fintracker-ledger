package com.fintracker.ledger.dashboard.service;

import com.fintracker.ledger.dashboard.model.DashboardSummary;

import java.util.UUID;

public interface DashboardService {

    DashboardSummary getAggregations(UUID userId);
}
