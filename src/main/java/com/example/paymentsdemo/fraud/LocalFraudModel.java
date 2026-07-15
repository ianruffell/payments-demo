package com.example.paymentsdemo.fraud;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained, deterministic fraud model for the demo (spec 011).
 *
 * <p>Scores anomaly relative to the customer's own context: deviation from their typical spend,
 * payment velocity, unfamiliar merchant/country, currency mismatch, recent declines, risk tier,
 * and the legacy heuristic as one input feature. Deterministic so demo runs are repeatable;
 * replaceable behind {@link FraudModel} by a real inference service.
 */
public class LocalFraudModel implements FraudModel {

    @Override
    public FraudDecision score(FraudFeatures f) {
        List<String> signals = new ArrayList<>();
        double score = 4.0;

        // Deviation from the customer's own spending pattern.
        if (f.coldStart() || f.historySize() < 3) {
            // Limited history: lean on the ratio against the (seeded) typical spend.
            if (f.amountVsTypicalRatio() > 3.0) {
                double contribution = Math.min(28.0, (f.amountVsTypicalRatio() - 3.0) * 6.0 + 10.0);
                score += contribution;
                signals.add("AMOUNT_FAR_ABOVE_TYPICAL(x%.1f)".formatted(f.amountVsTypicalRatio()));
            }
        } else if (f.amountZScore() > 1.5) {
            double contribution = Math.min(42.0, (f.amountZScore() - 1.5) * 11.0 + 6.0);
            score += contribution;
            signals.add("AMOUNT_DEVIATION(z=%.1f)".formatted(f.amountZScore()));
        }

        // Velocity: bursts of payments in a short window.
        if (f.velocityLastMinute() >= 4) {
            score += Math.min(24.0, (f.velocityLastMinute() - 3) * 6.0);
            signals.add("HIGH_VELOCITY(%d/min)".formatted(f.velocityLastMinute()));
        } else if (f.velocityLastTenMinutes() >= 12) {
            score += 10.0;
            signals.add("ELEVATED_VELOCITY(%d/10min)".formatted(f.velocityLastTenMinutes()));
        }

        // Unfamiliar counterparties for this customer.
        if (f.newMerchant() && f.amountVsTypicalRatio() > 1.5) {
            score += 6.0;
            signals.add("NEW_MERCHANT_HIGH_AMOUNT");
        }
        if (f.newCountry() && !f.coldStart()) {
            score += 8.0;
            signals.add("NEW_MERCHANT_COUNTRY");
        }
        if (f.currencyMismatch()) {
            score += 10.0;
            signals.add("NON_HOME_CURRENCY");
        }

        // Recent declines make further anomalies riskier.
        if (f.recentDeclines() > 0) {
            score += Math.min(18.0, f.recentDeclines() * 6.0);
            signals.add("RECENT_DECLINES(%d)".formatted(f.recentDeclines()));
        }

        // Profile risk tier.
        score += switch (f.riskTier()) {
            case LOW -> 0.0;
            case MEDIUM -> 6.0;
            case HIGH -> 14.0;
        };

        // Overnight activity is mildly riskier.
        if (f.hourOfDay() >= 0 && f.hourOfDay() < 5) {
            score += 4.0;
            signals.add("OVERNIGHT");
        }

        // Blend the legacy heuristic as one feature.
        score += f.legacyScore() * 0.15;

        return new FraudDecision(Math.max(0.0, Math.min(99.0, score)), signals);
    }
}
