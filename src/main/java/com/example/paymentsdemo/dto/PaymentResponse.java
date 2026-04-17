package com.example.paymentsdemo.dto;

import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;

public record PaymentResponse(
        String paymentId,
        String accountId,
        String merchantId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String declineReason,
        double fraudScore,
        boolean suspicious,
        long createdAtEpochMs,
        long updatedAtEpochMs,
        String message,
        boolean duplicate
) {
    public static PaymentResponse fromPayment(Payment payment, String message, boolean duplicate) {
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getAccountId(),
                payment.getMerchantId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getDeclineReason(),
                payment.getFraudScore(),
                payment.isSuspicious(),
                payment.getCreatedAtEpochMs(),
                payment.getUpdatedAtEpochMs(),
                message,
                duplicate
        );
    }
}
