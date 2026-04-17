package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Payment;

public record PaymentOperationResult(Payment payment, String message, boolean duplicate) {
}
