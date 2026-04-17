package com.example.paymentsdemo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AuthorizePaymentRequest(
        @NotBlank String paymentId,
        @NotBlank String accountId,
        @NotBlank String merchantId,
        @Min(1) long amountMinor,
        @NotBlank String currency
) {
}
