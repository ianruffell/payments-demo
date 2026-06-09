package com.example.paymentsdemo.dto;

import java.util.List;

public record SemanticInvestigationResponse(
        boolean available,
        String message,
        String query,
        String embeddingModel,
        long indexedPayments,
        List<SemanticInvestigationResult> results
) {
}
