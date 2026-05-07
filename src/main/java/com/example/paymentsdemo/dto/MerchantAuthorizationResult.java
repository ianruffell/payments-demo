package com.example.paymentsdemo.dto;

public record MerchantAuthorizationResult(
        String paymentId,
        String merchantId,
        boolean approved,
        String reason,
        String merchantReference,
        long respondedAtEpochMs
) {
}
