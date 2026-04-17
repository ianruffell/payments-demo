package com.example.paymentsdemo.dto;

public record MerchantVolume(
        String merchantId,
        String merchantName,
        long transactionCount,
        long amountMinor
) {
}
