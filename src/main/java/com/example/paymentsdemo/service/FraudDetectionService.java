package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.CustomerContext;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.fraud.FraudAssessment;
import com.example.paymentsdemo.fraud.FraudDecision;
import com.example.paymentsdemo.fraud.FraudFeatures;
import com.example.paymentsdemo.fraud.FraudModel;
import com.example.paymentsdemo.fraud.LocalFraudModel;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Real-time AI fraud gate (spec 011): scores each authorization against the customer's context
 * before merchant dispatch. Runs entirely on the hot path against GridGain and an in-process
 * model — no external calls. Model/context failure follows the configured fail policy instead
 * of crashing authorize.
 */
@Service
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class FraudDetectionService {

    public static final String FRAUD_DECLINE_REASON = "AI_FRAUD_BLOCK";
    public static final String UNAVAILABLE_DECLINE_REASON = "AI_FRAUD_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final CustomerContextService customerContextService;
    private final FraudService fraudService;
    private final FraudModel model;
    private final AtomicReference<Double> threshold;
    private final boolean failClosed;

    public FraudDetectionService(
            CustomerContextService customerContextService,
            FraudService fraudService,
            @Value("${demo.fraud.ai.threshold:75.0}") double threshold,
            @Value("${demo.fraud.ai.fail-policy:fail-open}") String failPolicy,
            @Value("${demo.fraud.ai.model:local}") String modelName
    ) {
        this.customerContextService = customerContextService;
        this.fraudService = fraudService;
        this.threshold = new AtomicReference<>(threshold);
        this.failClosed = "fail-closed".equalsIgnoreCase(failPolicy);
        this.model = createModel(modelName);
    }

    public FraudAssessment assess(Account account, Merchant merchant, long amountMinor, String currency, long nowEpochMs) {
        double legacyScore = fraudService.score(account, merchant, amountMinor);
        try {
            CustomerContext context = customerContextService.find(account.getAccountId());
            boolean coldStart = context == null;
            if (coldStart) {
                context = customerContextService.baselineFor(account, nowEpochMs, false);
            }

            FraudDecision decision = model.score(features(context, merchant, amountMinor, currency, nowEpochMs, legacyScore, coldStart));
            double currentThreshold = threshold.get();
            boolean rejected = decision.score() >= currentThreshold;
            boolean suspicious = decision.score() >= Math.max(55.0, currentThreshold - 8.0);

            return new FraudAssessment(
                    decision.score(),
                    rejected,
                    suspicious,
                    rejected ? FRAUD_DECLINE_REASON : null,
                    decision.signals()
            );
        } catch (Exception e) {
            log.warn("AI fraud scoring unavailable for {} ({}); applying {} policy.",
                    account.getAccountId(), e.toString(), failClosed ? "fail-closed" : "fail-open");
            if (failClosed) {
                return new FraudAssessment(99.0, true, true, UNAVAILABLE_DECLINE_REASON, List.of("MODEL_UNAVAILABLE"));
            }
            // Fail-open: fall back to the legacy heuristic so legitimate customers are not blocked.
            return new FraudAssessment(legacyScore, false, fraudService.isSuspicious(legacyScore), null, List.of("MODEL_UNAVAILABLE"));
        }
    }

    public double getThreshold() {
        return threshold.get();
    }

    public void setThreshold(double value) {
        threshold.set(value);
    }

    private FraudFeatures features(
            CustomerContext context,
            Merchant merchant,
            long amountMinor,
            String currency,
            long nowEpochMs,
            double legacyScore,
            boolean coldStart
    ) {
        double typical = Math.max(1.0, context.typicalSpendMinor());
        double stdDev = Math.max(1.0, context.spendStdDevMinor());

        return new FraudFeatures(
                amountMinor,
                typical,
                stdDev,
                (amountMinor - typical) / stdDev,
                amountMinor / typical,
                merchant == null || !context.hasSeenMerchant(merchant.getMerchantId()),
                merchant != null && merchant.getCountry() != null
                        && !context.getHistory().isEmpty()
                        && !context.hasSeenCountry(merchant.getCountry()),
                currency != null && context.getHomeCurrency() != null
                        && !currency.equalsIgnoreCase(context.getHomeCurrency()),
                context.paymentsSince(nowEpochMs - 60_000L),
                context.paymentsSince(nowEpochMs - 600_000L),
                context.recentDeclineCount(),
                context.getRiskTier(),
                legacyScore,
                Instant.ofEpochMilli(nowEpochMs).atZone(ZoneOffset.UTC).getHour(),
                coldStart,
                context.getHistory().size()
        );
    }

    private FraudModel createModel(String modelName) {
        if (!"local".equalsIgnoreCase(modelName)) {
            log.warn("Unknown fraud model '{}'; using the local model.", modelName);
        }
        return new LocalFraudModel();
    }
}
