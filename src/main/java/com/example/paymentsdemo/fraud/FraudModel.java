package com.example.paymentsdemo.fraud;

/**
 * Pluggable fraud scorer (spec 011). The demo ships {@link LocalFraudModel}; an external
 * ML/LLM inference service can implement this interface without changing the payment pipeline.
 */
public interface FraudModel {

    FraudDecision score(FraudFeatures features);
}
