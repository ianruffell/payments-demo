package com.example.paymentsdemo.dto;

import com.example.paymentsdemo.domain.PaymentStatus;

public record RecentSuspiciousPayment(
        String paymentId,
        String merchantId,
        long amountMinor,
        double fraudScore,
        PaymentStatus status,
        long createdAtEpochMs
) {
}
