package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.dto.TransactionFlowConnection;
import com.example.paymentsdemo.dto.TransactionFlowSnapshot;
import com.example.paymentsdemo.dto.TransactionFlowStageState;
import com.example.paymentsdemo.dto.TransactionFlowStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class TransactionFlowService {

    private static final long WINDOW_SECONDS = 300L;

    private final Ignite ignite;
    private final OracleSystemOfRecordRepository oracleRepository;

    public TransactionFlowService(Ignite ignite, OracleSystemOfRecordRepository oracleRepository) {
        this.ignite = ignite;
        this.oracleRepository = oracleRepository;
    }

    public TransactionFlowSnapshot snapshot() {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (WINDOW_SECONDS * 1_000L);

        List<PaymentHistoryRow> payments = recentPayments(windowStart);

        long totalTransactions = payments.size();
        long dispatchedToMerchant = 0L;
        long validationDeclined = 0L;
        long pendingMerchant = 0L;
        long approvedPath = 0L;
        long merchantDeclined = 0L;
        long timedOut = 0L;
        long authorized = 0L;
        long captured = 0L;
        long refunded = 0L;

        for (PaymentHistoryRow row : payments) {
            String status = row.status().name();
            boolean attempted = row.merchantAttempted();

            if (attempted) {
                dispatchedToMerchant++;
            }

            if (PaymentStatus.PENDING_MERCHANT.name().equals(status)) {
                pendingMerchant++;
            } else if (PaymentStatus.AUTHORIZED.name().equals(status)) {
                approvedPath++;
                authorized++;
            } else if (PaymentStatus.CAPTURED.name().equals(status)) {
                approvedPath++;
                captured++;
            } else if (PaymentStatus.REFUNDED.name().equals(status)) {
                approvedPath++;
                refunded++;
            } else if (PaymentStatus.TIMED_OUT.name().equals(status)) {
                timedOut++;
            } else if (PaymentStatus.DECLINED.name().equals(status)) {
                if (attempted) {
                    merchantDeclined++;
                } else {
                    validationDeclined++;
                }
            }
        }

        double approvalRate = totalTransactions == 0
                ? 0.0
                : Math.round((approvedPath * 1000.0) / totalTransactions) / 10.0;

        List<TransactionFlowStep> steps = List.of(
                new TransactionFlowStep(
                        "received",
                        "1. Received",
                        "New authorization requests entering the processor",
                        totalTransactions,
                        List.of(
                                new TransactionFlowStageState("Created", totalTransactions, "brand")
                        )
                ),
                new TransactionFlowStep(
                        "screening",
                        "2. Initial Screening",
                        "Requests either pass into merchant review or fail immediately",
                        totalTransactions,
                        List.of(
                                new TransactionFlowStageState("Sent to merchant", dispatchedToMerchant, "brand"),
                                new TransactionFlowStageState("Declined before merchant", validationDeclined, "warning")
                        )
                ),
                new TransactionFlowStep(
                        "merchant",
                        "3. Merchant Review",
                        "Live merchant decision outcomes for dispatched requests",
                        dispatchedToMerchant,
                        List.of(
                                new TransactionFlowStageState("Awaiting response", pendingMerchant, "muted"),
                                new TransactionFlowStageState("Approved path", approvedPath, "success"),
                                new TransactionFlowStageState("Declined", merchantDeclined, "warning"),
                                new TransactionFlowStageState("Timed out", timedOut, "danger")
                        )
                ),
                new TransactionFlowStep(
                        "settlement",
                        "4. Settlement State",
                        "Where approved transactions currently sit downstream",
                        approvedPath,
                        List.of(
                                new TransactionFlowStageState("Authorized", authorized, "success"),
                                new TransactionFlowStageState("Captured", captured, "brand"),
                                new TransactionFlowStageState("Refunded", refunded, "muted")
                        )
                )
        );

        List<TransactionFlowConnection> connections = List.of(
                new TransactionFlowConnection("received", "screening", "ingested", totalTransactions),
                new TransactionFlowConnection("screening", "merchant", "forwarded", dispatchedToMerchant),
                new TransactionFlowConnection("merchant", "settlement", "approved", approvedPath)
        );

        return new TransactionFlowSnapshot(
                now,
                WINDOW_SECONDS,
                totalTransactions,
                pendingMerchant,
                approvalRate,
                steps,
                connections
        );
    }

    private List<PaymentHistoryRow> recentPayments(long windowStart) {
        List<List<?>> attemptRows = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS)
                .query(new SqlFieldsQuery(
                        "SELECT paymentId FROM MerchantPaymentAttempt WHERE requestedAtEpochMs >= ?"
                ).setArgs(windowStart))
                .getAll();

        Set<String> attempted = new HashSet<>(attemptRows.size());
        for (List<?> row : attemptRows) {
            if (!row.isEmpty() && row.get(0) != null) {
                attempted.add(String.valueOf(row.get(0)));
            }
        }

        Map<String, PaymentHistoryRow> payments = new LinkedHashMap<>();
        List<List<?>> rows = ignite.cache(CacheNames.PAYMENTS)
                .query(new SqlFieldsQuery(
                        "SELECT paymentId, merchantId, amountMinor, status, declineReason, fraudScore, suspicious, createdAtEpochMs " +
                                "FROM Payment WHERE createdAtEpochMs >= ?"
                ).setArgs(windowStart))
                .getAll();

        for (List<?> row : rows) {
            String paymentId = String.valueOf(row.get(0));
            payments.put(paymentId, new PaymentHistoryRow(
                    paymentId,
                    String.valueOf(row.get(1)),
                    ((Number) row.get(2)).longValue(),
                    PaymentStatus.valueOf(String.valueOf(row.get(3))),
                    row.get(4) == null ? null : String.valueOf(row.get(4)),
                    ((Number) row.get(5)).doubleValue(),
                    Boolean.TRUE.equals(row.get(6)),
                    ((Number) row.get(7)).longValue(),
                    attempted.contains(paymentId)
            ));
        }

        for (PaymentHistoryRow row : oracleRepository.loadRecentArchivedPayments(windowStart)) {
            payments.putIfAbsent(row.paymentId(), row);
        }

        return new ArrayList<>(payments.values());
    }
}
