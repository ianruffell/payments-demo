package com.example.paymentsdemo.dto;

import java.util.List;

public record TransactionFlowStep(
        String id,
        String title,
        String description,
        long total,
        List<TransactionFlowStageState> states
) {
}
