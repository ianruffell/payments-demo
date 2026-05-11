package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.PaymentStatus;

public record PaymentHistoryRow(
        String paymentId,
        String merchantId,
        long amountMinor,
        PaymentStatus status,
        String declineReason,
        double fraudScore,
        boolean suspicious,
        long createdAtEpochMs,
        boolean merchantAttempted
) {
}
