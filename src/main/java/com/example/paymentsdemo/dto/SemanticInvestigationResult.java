package com.example.paymentsdemo.dto;

public record SemanticInvestigationResult(
        String paymentId,
        String summary,
        String status,
        String merchantId,
        long amountMinor,
        String currency,
        double fraudScore,
        boolean suspicious,
        String declineReason,
        long createdAtEpochMs,
        String source,
        double distance,
        double relevancePercent
) {
}
