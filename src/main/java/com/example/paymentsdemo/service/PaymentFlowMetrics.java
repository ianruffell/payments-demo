package com.example.paymentsdemo.service;

import com.example.paymentsdemo.dto.DashboardSnapshot;
import com.example.paymentsdemo.dto.DeclineReasonCount;
import com.example.paymentsdemo.dto.TransactionFlowSnapshot;
import com.example.paymentsdemo.dto.TransactionFlowStageState;
import com.example.paymentsdemo.dto.TransactionFlowStep;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes payments-flow metrics for Prometheus (spec 010).
 *
 * <p>This is a read-only bridge: it periodically reads the same stage/outcome model the
 * transaction-flow view already computes and exposes it as Micrometer gauges. It never
 * touches the payment hot path, and labels are bounded ({@code stage}, {@code outcome},
 * {@code reason}) to keep cardinality low.
 *
 * <p>Runs only in the processor role, where the flow/dashboard services and the web/metrics
 * endpoint exist. Uses a dedicated single-thread scheduler so it does not depend on
 * application-wide Spring scheduling.
 */
@Component
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class PaymentFlowMetrics {

    private static final Logger log = LoggerFactory.getLogger(PaymentFlowMetrics.class);
    private static final long REFRESH_INTERVAL_SECONDS = 5L;

    /** stepId -> (outcome tag -> state label emitted by TransactionFlowService). */
    private static final Map<String, Map<String, String>> STAGE_OUTCOMES = new LinkedHashMap<>();

    static {
        STAGE_OUTCOMES.put("received", Map.of("created", "Created"));
        STAGE_OUTCOMES.put("screening", new LinkedHashMap<>() {{
            put("sent_to_merchant", "Sent to merchant");
            put("declined_before_merchant", "Declined before merchant");
        }});
        STAGE_OUTCOMES.put("merchant", new LinkedHashMap<>() {{
            put("awaiting", "Awaiting response");
            put("approved", "Approved path");
            put("declined", "Declined");
            put("timed_out", "Timed out");
        }});
        STAGE_OUTCOMES.put("settlement", new LinkedHashMap<>() {{
            put("authorized", "Authorized");
            put("captured", "Captured");
            put("refunded", "Refunded");
        }});
    }

    private final MeterRegistry registry;
    private final TransactionFlowService transactionFlowService;
    private final DashboardService dashboardService;
    private final FraudService fraudService;

    private final Map<String, AtomicLong> stageOutcome = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> declineReasons = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong pendingMerchant = new AtomicLong();
    private final AtomicReference<Double> approvalRate = new AtomicReference<>(0.0);
    private final AtomicReference<Double> fraudThreshold = new AtomicReference<>(0.0);

    private ScheduledExecutorService scheduler;

    public PaymentFlowMetrics(MeterRegistry registry,
                              TransactionFlowService transactionFlowService,
                              DashboardService dashboardService,
                              FraudService fraudService) {
        this.registry = registry;
        this.transactionFlowService = transactionFlowService;
        this.dashboardService = dashboardService;
        this.fraudService = fraudService;
    }

    @PostConstruct
    void start() {
        Gauge.builder("payments.flow.total", total, AtomicLong::doubleValue)
                .description("Payments observed in the transaction-flow window")
                .register(registry);
        Gauge.builder("payments.flow.pending.merchant", pendingMerchant, AtomicLong::doubleValue)
                .description("Payments currently awaiting a merchant decision")
                .register(registry);
        Gauge.builder("payments.approval.rate", approvalRate, AtomicReference::get)
                .description("Approval rate over the last five minutes (percent)")
                .register(registry);
        Gauge.builder("payments.fraud.threshold", fraudThreshold, AtomicReference::get)
                .description("Current fraud score threshold")
                .register(registry);

        // Pre-register every stage/outcome series so panels have data from the first scrape.
        STAGE_OUTCOMES.forEach((stage, outcomes) ->
                outcomes.keySet().forEach(outcome -> stageOutcomeHolder(stage, outcome)));

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "payment-flow-metrics");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshQuietly, 0L, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Payment-flow metrics publishing every {}s", REFRESH_INTERVAL_SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception ex) {
            // Metrics must never destabilise the processor; log and try again next tick.
            log.debug("Skipped a payment-flow metrics refresh: {}", ex.toString());
        }
    }

    private void refresh() {
        TransactionFlowSnapshot flow = transactionFlowService.snapshot();
        total.set(flow.totalTransactions());
        pendingMerchant.set(flow.inFlightTransactions());
        approvalRate.set(flow.approvalRateLastFiveMinutes());

        Map<String, TransactionFlowStep> stepsById = new LinkedHashMap<>();
        for (TransactionFlowStep step : flow.steps()) {
            stepsById.put(step.id(), step);
        }
        STAGE_OUTCOMES.forEach((stage, outcomes) -> {
            TransactionFlowStep step = stepsById.get(stage);
            outcomes.forEach((outcome, stateLabel) ->
                    stageOutcomeHolder(stage, outcome).set(stateCount(step, stateLabel)));
        });

        DashboardSnapshot dashboard = dashboardService.snapshot();
        fraudThreshold.set(dashboard.fraudThreshold() > 0 ? dashboard.fraudThreshold() : fraudService.getThreshold());
        updateDeclineReasons(dashboard.declinesByReason());
    }

    private long stateCount(TransactionFlowStep step, String label) {
        if (step == null) {
            return 0L;
        }
        for (TransactionFlowStageState state : step.states()) {
            if (state.label().equals(label)) {
                return state.count();
            }
        }
        return 0L;
    }

    private AtomicLong stageOutcomeHolder(String stage, String outcome) {
        return stageOutcome.computeIfAbsent(stage + "/" + outcome, key -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder("payments.flow.transactions", holder, AtomicLong::doubleValue)
                    .description("Payments per flow stage and outcome in the current window")
                    .tags(Tags.of("stage", stage, "outcome", outcome))
                    .register(registry);
            return holder;
        });
    }

    private void updateDeclineReasons(List<DeclineReasonCount> declines) {
        // Zero out previously-seen reasons so a reason that drops off the window reports 0.
        declineReasons.values().forEach(holder -> holder.set(0L));
        if (declines == null) {
            return;
        }
        for (DeclineReasonCount decline : declines) {
            String reason = decline.reason() == null || decline.reason().isBlank() ? "unknown" : decline.reason();
            declineReasonHolder(reason).set(decline.count());
        }
    }

    private AtomicLong declineReasonHolder(String reason) {
        return declineReasons.computeIfAbsent(reason, key -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder("payments.decline.reasons", holder, AtomicLong::doubleValue)
                    .description("Declined payments by reason in the current window")
                    .tags(Tags.of("reason", key))
                    .register(registry);
            return holder;
        });
    }
}
