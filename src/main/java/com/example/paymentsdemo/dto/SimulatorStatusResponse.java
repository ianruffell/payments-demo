package com.example.paymentsdemo.dto;

public record SimulatorStatusResponse(
        boolean running,
        int ratePerSecond,
        long generatedPayments
) {
}
