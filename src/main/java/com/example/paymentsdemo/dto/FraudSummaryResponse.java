package com.example.paymentsdemo.dto;

/** Fraud activity summary for the Fraud Detection page (spec 011). */
public record FraudSummaryResponse(
        long screened,
        long blocked,
        long suspicious,
        double blockRatePercent,
        double threshold,
        int recentWindow
) {
}
