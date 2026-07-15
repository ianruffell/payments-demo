package com.example.paymentsdemo.fraud;

import com.example.paymentsdemo.domain.RiskTier;

/**
 * Model input for one authorization: the current payment's attributes combined with signals
 * derived from the customer's context (spec 011).
 */
public record FraudFeatures(
        long amountMinor,
        double typicalSpendMinor,
        double spendStdDevMinor,
        double amountZScore,
        double amountVsTypicalRatio,
        boolean newMerchant,
        boolean newCountry,
        boolean currencyMismatch,
        int velocityLastMinute,
        int velocityLastTenMinutes,
        int recentDeclines,
        RiskTier riskTier,
        double legacyScore,
        int hourOfDay,
        boolean coldStart,
        int historySize
) {
}
