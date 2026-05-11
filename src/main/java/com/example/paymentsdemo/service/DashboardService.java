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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class DashboardService {

    private final Ignite ignite;
    private final FraudService fraudService;
    private final SimulatorGatewayService simulatorGatewayService;
    private final OracleSystemOfRecordRepository oracleRepository;

    public DashboardService(
            Ignite ignite,
            FraudService fraudService,
            SimulatorGatewayService simulatorGatewayService,
            OracleSystemOfRecordRepository oracleRepository
    ) {
        this.ignite = ignite;
        this.fraudService = fraudService;
        this.simulatorGatewayService = simulatorGatewayService;
        this.oracleRepository = oracleRepository;
    }

    public DashboardSnapshot snapshot() {
        long now = Instant.now().toEpochMilli();
        long lastMinute = now - 60_000L;
        long lastFiveMinutes = now - 300_000L;

        List<PaymentHistoryRow> recentPayments = recentPayments(lastFiveMinutes);

        Map<String, Long> statusCounts = recentPayments.stream()
                .collect(Collectors.groupingBy(row -> row.status().name(), Collectors.counting()));

        long approved = statusCounts.getOrDefault(PaymentStatus.AUTHORIZED.name(), 0L)
                + statusCounts.getOrDefault(PaymentStatus.CAPTURED.name(), 0L)
                + statusCounts.getOrDefault(PaymentStatus.REFUNDED.name(), 0L);
        long total = recentPayments.size();
        double approvalRate = total == 0 ? 0.0 : (approved * 100.0) / total;

        long throughputLastMinute = recentPayments.stream()
                .filter(row -> row.createdAtEpochMs() >= lastMinute)
                .count();

        List<DeclineReasonCount> declines = recentPayments.stream()
                .filter(row -> row.status() == PaymentStatus.DECLINED)
                .collect(Collectors.groupingBy(PaymentHistoryRow::declineReason, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new DeclineReasonCount(entry.getKey(), entry.getValue()))
                .toList();

        List<MerchantVolume> topMerchants = topMerchants(recentPayments);
        List<RecentSuspiciousPayment> suspiciousPayments = recentPayments.stream()
                .filter(PaymentHistoryRow::suspicious)
                .filter(row -> row.status() != PaymentStatus.PENDING_MERCHANT)
                .sorted(Comparator.comparingLong(PaymentHistoryRow::createdAtEpochMs).reversed())
                .limit(10)
                .map(row -> new RecentSuspiciousPayment(
                        row.paymentId(),
                        row.merchantId(),
                        row.amountMinor(),
                        row.fraudScore(),
                        row.status(),
                        row.createdAtEpochMs()
                ))
                .toList();

        List<ThroughputPoint> throughputSeries = throughputSeries(recentPayments, now);
        SimulatorStatusResponse simulatorStatus = simulatorGatewayService.status();

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

    private List<MerchantVolume> topMerchants(List<PaymentHistoryRow> payments) {
        Map<String, MerchantAccumulator> accumulators = new HashMap<>();
        for (PaymentHistoryRow row : payments) {
            String merchantId = row.merchantId();
            MerchantAccumulator accumulator = accumulators.computeIfAbsent(merchantId, ignored -> new MerchantAccumulator());
            accumulator.count++;
            accumulator.amountMinor += row.amountMinor();
        }

        return accumulators.entrySet().stream()
                .sorted((left, right) -> {
                    int byAmount = Long.compare(right.getValue().amountMinor, left.getValue().amountMinor);
                    if (byAmount != 0) {
                        return byAmount;
                    }
                    return Long.compare(right.getValue().count, left.getValue().count);
                })
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

    private List<ThroughputPoint> throughputSeries(List<PaymentHistoryRow> payments, long now) {
        long currentSecond = now / 1_000L;
        Map<Long, Long> buckets = new LinkedHashMap<>();
        for (long second = currentSecond - 59; second <= currentSecond; second++) {
            buckets.put(second, 0L);
        }

        for (PaymentHistoryRow row : payments) {
            long second = row.createdAtEpochMs() / 1_000L;
            buckets.computeIfPresent(second, (ignored, count) -> count + 1L);
        }

        List<ThroughputPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((bucket, count) -> points.add(new ThroughputPoint(bucket, count)));
        return points;
    }

    private List<PaymentHistoryRow> recentPayments(long windowStart) {
        Map<String, Boolean> attempted = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS)
                .query(new SqlFieldsQuery(
                        "SELECT paymentId FROM MerchantPaymentAttempt WHERE requestedAtEpochMs >= ?"
                ).setArgs(windowStart))
                .getAll()
                .stream()
                .filter(row -> !row.isEmpty() && row.get(0) != null)
                .collect(Collectors.toMap(row -> String.valueOf(row.get(0)), row -> true, (left, ignored) -> left));

        Map<String, PaymentHistoryRow> payments = ignite.cache(CacheNames.PAYMENTS)
                .query(new SqlFieldsQuery(
                        "SELECT paymentId, merchantId, amountMinor, status, declineReason, fraudScore, suspicious, createdAtEpochMs " +
                                "FROM Payment WHERE createdAtEpochMs >= ?"
                ).setArgs(windowStart))
                .getAll()
                .stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get(0)),
                        row -> new PaymentHistoryRow(
                                String.valueOf(row.get(0)),
                                String.valueOf(row.get(1)),
                                ((Number) row.get(2)).longValue(),
                                PaymentStatus.valueOf(String.valueOf(row.get(3))),
                                row.get(4) == null ? null : String.valueOf(row.get(4)),
                                ((Number) row.get(5)).doubleValue(),
                                Boolean.TRUE.equals(row.get(6)),
                                ((Number) row.get(7)).longValue(),
                                attempted.containsKey(String.valueOf(row.get(0)))
                        )
                ));

        for (PaymentHistoryRow row : oracleRepository.loadRecentArchivedPayments(windowStart)) {
            payments.putIfAbsent(row.paymentId(), row);
        }

        return new ArrayList<>(payments.values());
    }

    private static class MerchantAccumulator {
        private long count;
        private long amountMinor;
    }
}
