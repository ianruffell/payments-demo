package com.example.paymentsdemo.fraud;

import java.util.List;

/**
 * Model output: a fraud score on a 0-99 scale (higher = riskier) plus the signals that
 * contributed, for explainability in the demo.
 */
public record FraudDecision(double score, List<String> signals) {
}
