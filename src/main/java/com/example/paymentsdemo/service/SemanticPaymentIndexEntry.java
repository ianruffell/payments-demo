package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.PaymentStatus;

public record SemanticPaymentIndexEntry(
        String paymentId,
        String summary,
        PaymentStatus status,
        String merchantId,
        long amountMinor,
        String currency,
        double fraudScore,
        boolean suspicious,
        String declineReason,
        long createdAtEpochMs,
        String source,
        String embeddingJson
) {
}
