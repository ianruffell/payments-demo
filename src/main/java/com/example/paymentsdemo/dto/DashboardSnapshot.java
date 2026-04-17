package com.example.paymentsdemo.dto;

import java.util.List;
import java.util.Map;

public record DashboardSnapshot(
        long generatedAtEpochMs,
        long throughputLastMinute,
        double approvalRateLastFiveMinutes,
        Map<String, Long> statusCountsLastFiveMinutes,
        List<DeclineReasonCount> declinesByReason,
        List<MerchantVolume> topMerchants,
        List<RecentSuspiciousPayment> suspiciousPayments,
        List<ThroughputPoint> throughputSeries,
        SimulatorStatusResponse simulator,
        double fraudThreshold
) {
}
