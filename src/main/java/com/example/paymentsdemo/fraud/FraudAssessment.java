package com.example.paymentsdemo.fraud;

import java.util.List;

/**
 * Outcome of the AI fraud gate for one authorization: the score, the verdict against the
 * configured threshold, and the contributing signals. {@code declineReason} is non-null only
 * when the payment must be rejected before merchant dispatch.
 */
public record FraudAssessment(
        double score,
        boolean rejected,
        boolean suspicious,
        String declineReason,
        List<String> signals
) {
}
