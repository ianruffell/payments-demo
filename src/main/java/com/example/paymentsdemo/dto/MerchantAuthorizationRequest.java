package com.example.paymentsdemo.dto;

public record MerchantAuthorizationRequest(
        String paymentId,
        String accountId,
        String merchantId,
        long amountMinor,
        String currency,
        long createdAtEpochMs,
        long deadlineEpochMs,
        String callbackUrl
) {
}
