package com.example.paymentsdemo.dto;

import java.util.List;

public record TransactionFlowSnapshot(
        long generatedAtEpochMs,
        long windowSeconds,
        long totalTransactions,
        long inFlightTransactions,
        double approvalRateLastFiveMinutes,
        List<TransactionFlowStep> steps,
        List<TransactionFlowConnection> connections
) {
}
