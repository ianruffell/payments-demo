package com.example.paymentsdemo.dto;

public record TransactionFlowStageState(
        String label,
        long count,
        String accent
) {
}
