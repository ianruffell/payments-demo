package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.dto.DashboardSnapshot;
import com.example.paymentsdemo.dto.DeclineReasonCount;
import com.example.paymentsdemo.dto.MerchantVolume;
import com.example.paymentsdemo.dto.RecentSuspiciousPayment;
import com.example.paymentsdemo.dto.SimulatorStatusResponse;
import com.example.paymentsdemo.dto.ThroughputPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

    private final Ignite ignite;
    private final FraudService fraudService;
    private final com.example.paymentsdemo.simulator.PaymentSimulator paymentSimulator;

    public DashboardService(
            Ignite ignite,
            FraudService fraudService,
            com.example.paymentsdemo.simulator.PaymentSimulator paymentSimulator
    ) {
        this.ignite = ignite;
        this.fraudService = fraudService;
        this.paymentSimulator = paymentSimulator;
    }

    public DashboardSnapshot snapshot() {
        long now = Instant.now().toEpochMilli();
        long lastMinute = now - 60_000L;
        long lastFiveMinutes = now - 300_000L;

        List<List<?>> recentPayments = query(
                "SELECT paymentId, merchantId, amountMinor, status, declineReason, fraudScore, suspicious, createdAtEpochMs " +
                        "FROM Payment WHERE createdAtEpochMs >= ?",
                lastFiveMinutes
        );

        Map<String, Long> statusCounts = recentPayments.stream()
                .collect(Collectors.groupingBy(row -> String.valueOf(row.get(3)), Collectors.counting()));

        long approved = statusCounts.getOrDefault(PaymentStatus.AUTHORIZED.name(), 0L)
                + statusCounts.getOrDefault(PaymentStatus.CAPTURED.name(), 0L)
                + statusCounts.getOrDefault(PaymentStatus.REFUNDED.name(), 0L);
        long total = recentPayments.size();
        double approvalRate = total == 0 ? 0.0 : (approved * 100.0) / total;

        long throughputLastMinute = recentPayments.stream()
                .filter(row -> ((Number) row.get(7)).longValue() >= lastMinute)
                .count();

        List<DeclineReasonCount> declines = recentPayments.stream()
                .filter(row -> PaymentStatus.DECLINED.name().equals(String.valueOf(row.get(3))))
                .collect(Collectors.groupingBy(row -> String.valueOf(row.get(4)), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new DeclineReasonCount(entry.getKey(), entry.getValue()))
                .toList();

        List<MerchantVolume> topMerchants = topMerchants(recentPayments);
        List<RecentSuspiciousPayment> suspiciousPayments = recentPayments.stream()
                .filter(row -> Boolean.TRUE.equals(row.get(6)))
                .sorted(Comparator.comparingLong((List<?> row) -> ((Number) row.get(7)).longValue()).reversed())
                .limit(10)
                .map(row -> new RecentSuspiciousPayment(
                        String.valueOf(row.get(0)),
                        String.valueOf(row.get(1)),
                        ((Number) row.get(2)).longValue(),
                        ((Number) row.get(5)).doubleValue(),
                        PaymentStatus.valueOf(String.valueOf(row.get(3))),
                        ((Number) row.get(7)).longValue()
                ))
                .toList();

        List<ThroughputPoint> throughputSeries = throughputSeries(recentPayments, now);
        SimulatorStatusResponse simulatorStatus = new SimulatorStatusResponse(
                paymentSimulator.isRunning(),
                paymentSimulator.getRatePerSecond(),
                paymentSimulator.getGeneratedPayments()
        );

        return new DashboardSnapshot(
                now,
                throughputLastMinute,
                Math.round(approvalRate * 10.0) / 10.0,
                statusCounts,
                declines,
                topMerchants,
                suspiciousPayments,
                throughputSeries,
                simulatorStatus,
                fraudService.getThreshold()
        );
    }

    private List<MerchantVolume> topMerchants(List<List<?>> payments) {
        Map<String, MerchantAccumulator> accumulators = new HashMap<>();
        for (List<?> row : payments) {
            String merchantId = String.valueOf(row.get(1));
            MerchantAccumulator accumulator = accumulators.computeIfAbsent(merchantId, ignored -> new MerchantAccumulator());
            accumulator.count++;
            accumulator.amountMinor += ((Number) row.get(2)).longValue();
        }

        return accumulators.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().count, left.getValue().count))
                .limit(5)
                .map(entry -> {
                    Merchant merchant = (Merchant) ignite.cache(CacheNames.MERCHANTS).get(entry.getKey());
                    return new MerchantVolume(
                            entry.getKey(),
                            merchant == null ? entry.getKey() : merchant.getName(),
                            entry.getValue().count,
                            entry.getValue().amountMinor
                    );
                })
                .toList();
    }

    private List<ThroughputPoint> throughputSeries(List<List<?>> payments, long now) {
        long currentSecond = now / 1_000L;
        Map<Long, Long> buckets = new LinkedHashMap<>();
        for (long second = currentSecond - 59; second <= currentSecond; second++) {
            buckets.put(second, 0L);
        }

        for (List<?> row : payments) {
            long second = ((Number) row.get(7)).longValue() / 1_000L;
            buckets.computeIfPresent(second, (ignored, count) -> count + 1L);
        }

        List<ThroughputPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((bucket, count) -> points.add(new ThroughputPoint(bucket, count)));
        return points;
    }

    private List<List<?>> query(String sql, Object... args) {
        return ignite.cache(CacheNames.PAYMENTS)
                .query(new SqlFieldsQuery(sql).setArgs(args))
                .getAll();
    }

    private static class MerchantAccumulator {
        private long count;
        private long amountMinor;
    }
}
