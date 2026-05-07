package com.example.paymentsdemo.domain;

public enum PaymentStatus {
    AUTHORIZED,
    CAPTURED,
    DECLINED,
    REFUNDED,
    PENDING_MERCHANT,
    TIMED_OUT
}
