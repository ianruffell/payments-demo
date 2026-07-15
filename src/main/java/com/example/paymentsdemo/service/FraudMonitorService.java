package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.dto.BlockedPaymentView;
import com.example.paymentsdemo.dto.FraudSummaryResponse;
import com.example.paymentsdemo.fraud.FraudAssessment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * In-memory fraud-activity monitor backing the Fraud Detection page (spec 011).
 *
 * <p>Holds running counters and a bounded ring buffer of the most recent blocked payments, with
 * the contributing signals (which are not persisted on {@link Payment}). Live-view state only —
 * it resets on restart and is intentionally not part of the durable data model.
 */
@Service
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class FraudMonitorService {

    private final AtomicLong screened = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong suspicious = new AtomicLong();

    private final int maxRecent;
    private final Deque<BlockedPaymentView> recentBlocked = new ArrayDeque<>();

    private final FraudDetectionService fraudDetectionService;

    public FraudMonitorService(
            FraudDetectionService fraudDetectionService,
            @Value("${demo.fraud.ai.monitor-size:100}") int maxRecent
    ) {
        this.fraudDetectionService = fraudDetectionService;
        this.maxRecent = Math.max(1, maxRecent);
    }

    /** Record one decided authorization: the payment plus the fraud assessment that produced it. */
    public void record(Payment payment, FraudAssessment assessment) {
        screened.incrementAndGet();
        if (assessment.suspicious()) {
            suspicious.incrementAndGet();
        }

        boolean blockedByAi = FraudDetectionService.FRAUD_DECLINE_REASON.equals(payment.getDeclineReason())
                || FraudDetectionService.UNAVAILABLE_DECLINE_REASON.equals(payment.getDeclineReason());
        if (!blockedByAi) {
            return;
        }

        blocked.incrementAndGet();
        BlockedPaymentView view = new BlockedPaymentView(
                payment.getPaymentId(),
                payment.getAccountId(),
                payment.getMerchantId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getFraudScore(),
                payment.getDeclineReason(),
                assessment.signals() == null ? List.of() : List.copyOf(assessment.signals()),
                payment.getUpdatedAtEpochMs()
        );
        synchronized (recentBlocked) {
            recentBlocked.addFirst(view);
            while (recentBlocked.size() > maxRecent) {
                recentBlocked.removeLast();
            }
        }
    }

    public FraudSummaryResponse summary() {
        long s = screened.get();
        long b = blocked.get();
        double rate = s == 0 ? 0.0 : Math.round((b * 1000.0) / s) / 10.0;
        return new FraudSummaryResponse(s, b, suspicious.get(), rate, fraudDetectionService.getThreshold(), maxRecent);
    }

    public List<BlockedPaymentView> recentBlocked() {
        synchronized (recentBlocked) {
            return new ArrayList<>(recentBlocked);
        }
    }
}
