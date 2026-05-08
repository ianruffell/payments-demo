package com.example.paymentsdemo.dto;

public record TransactionFlowConnection(
        String fromStepId,
        String toStepId,
        String label,
        long count
) {
}
