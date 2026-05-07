package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator")
public class FraudService {

    private static final Set<String> HIGH_RISK_CATEGORIES = Set.of("GAMBLING", "CRYPTO", "TRAVEL", "DIGITAL_GOODS");
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of("NG", "RU", "UA", "BR");

    private final AtomicReference<Double> threshold;

    public FraudService(@Value("${demo.fraud.threshold:72.0}") double threshold) {
        this.threshold = new AtomicReference<>(threshold);
    }

    public double score(Account account, Merchant merchant, long amountMinor) {
        double score = 5.0;
        score += Math.min(25.0, amountMinor / 2_000.0);
        score += riskPenalty(account.getRiskTier());

        if (HIGH_RISK_CATEGORIES.contains(merchant.getCategory())) {
            score += 18.0;
        }

        if (HIGH_RISK_COUNTRIES.contains(merchant.getCountry())) {
            score += 10.0;
        }

        score += ThreadLocalRandom.current().nextDouble(0.0, 9.0);
        return Math.min(99.0, score);
    }

    public boolean isFraudulent(double score) {
        return score >= threshold.get();
    }

    public boolean isSuspicious(double score) {
        return score >= Math.max(55.0, threshold.get() - 8.0);
    }

    public double getThreshold() {
        return threshold.get();
    }

    public void setThreshold(double value) {
        threshold.set(value);
    }

    private double riskPenalty(RiskTier riskTier) {
        return switch (riskTier) {
            case LOW -> 5.0;
            case MEDIUM -> 15.0;
            case HIGH -> 28.0;
        };
    }
}
