package com.example.paymentsdemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SemanticInvestigationRequest(
        @NotBlank @Size(max = 240) String query,
        Integer limit
) {

    public int normalizedLimit() {
        if (limit == null) {
            return 8;
        }
        return Math.max(1, Math.min(limit, 20));
    }
}
