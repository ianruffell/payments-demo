package com.example.paymentsdemo.dto;

import java.util.List;

/** One row in the Fraud Detection page's blocked-payments table (spec 011). */
public record BlockedPaymentView(
        String paymentId,
        String accountId,
        String merchantId,
        long amountMinor,
        String currency,
        double fraudScore,
        String reason,
        List<String> signals,
        long blockedAtEpochMs
) {
}
