package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.MerchantPaymentAttempt;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class CompletedPaymentArchiveService {

    private static final Logger log = LoggerFactory.getLogger(CompletedPaymentArchiveService.class);

    private final Ignite ignite;
    private final OracleSystemOfRecordRepository oracleRepository;
    private final long pollIntervalMs;
    private final long capturedRetentionMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public CompletedPaymentArchiveService(
            Ignite ignite,
            OracleSystemOfRecordRepository oracleRepository,
            @Value("${demo.oracle.archive.poll-interval-ms:1000}") long pollIntervalMs,
            @Value("${demo.oracle.archive.captured-retention-ms:15000}") long capturedRetentionMs
    ) {
        this.ignite = ignite;
        this.oracleRepository = oracleRepository;
        this.pollIntervalMs = pollIntervalMs;
        this.capturedRetentionMs = capturedRetentionMs;
    }

    @PostConstruct
    public void start() {
        executor.scheduleAtFixedRate(this::archiveEligiblePayments, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    void archiveEligiblePayments() {
        long now = Instant.now().toEpochMilli();
        List<List<?>> rows = ignite.cache(CacheNames.PAYMENTS)
                .query(new SqlFieldsQuery(
                        "SELECT paymentId, status, updatedAtEpochMs " +
                                "FROM Payment " +
                                "WHERE status IN (?, ?, ?, ?)"
                ).setArgs(
                        PaymentStatus.DECLINED,
                        PaymentStatus.TIMED_OUT,
                        PaymentStatus.REFUNDED,
                        PaymentStatus.CAPTURED
                ))
                .getAll();

        for (List<?> row : rows) {
            if (row.size() < 3 || row.get(0) == null || row.get(1) == null || row.get(2) == null) {
                continue;
            }

            String paymentId = String.valueOf(row.get(0));
            PaymentStatus status = PaymentStatus.valueOf(String.valueOf(row.get(1)));
            long updatedAtEpochMs = ((Number) row.get(2)).longValue();

            if (status == PaymentStatus.CAPTURED && updatedAtEpochMs > now - capturedRetentionMs) {
                continue;
            }

            try {
                archivePayment(paymentId);
            } catch (RuntimeException e) {
                log.warn("Failed to archive completed payment {}", paymentId, e);
            }
        }
    }

    private void archivePayment(String paymentId) {
        Payment payment = (Payment) ignite.cache(CacheNames.PAYMENTS).get(paymentId);
        if (payment == null || !isEligible(payment, Instant.now().toEpochMilli())) {
            return;
        }

        MerchantPaymentAttempt attempt = (MerchantPaymentAttempt) ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS).get(paymentId);
        List<LedgerEntry> ledgerEntries = loadLedgerEntries(paymentId);
        if (!oracleRepository.archiveCompletedPayment(payment, attempt, ledgerEntries)) {
            removeFromGridGain(paymentId);
            return;
        }

        removeFromGridGain(paymentId);
    }

    private boolean isEligible(Payment payment, long now) {
        return switch (payment.getStatus()) {
            case DECLINED, TIMED_OUT, REFUNDED -> true;
            case CAPTURED -> payment.getUpdatedAtEpochMs() <= now - capturedRetentionMs;
            default -> false;
        };
    }

    private List<LedgerEntry> loadLedgerEntries(String paymentId) {
        List<List<?>> rows = ignite.cache(CacheNames.LEDGER_ENTRIES)
                .query(new SqlFieldsQuery(
                        "SELECT entryId, paymentId, accountId, merchantId, direction, amountMinor, currency, entryType, createdAtEpochMs " +
                                "FROM LedgerEntry WHERE paymentId = ?"
                ).setArgs(paymentId))
                .getAll();

        return rows.stream()
                .map(row -> new LedgerEntry(
                        String.valueOf(row.get(0)),
                        String.valueOf(row.get(1)),
                        String.valueOf(row.get(2)),
                        String.valueOf(row.get(3)),
                        com.example.paymentsdemo.domain.LedgerDirection.valueOf(String.valueOf(row.get(4))),
                        ((Number) row.get(5)).longValue(),
                        String.valueOf(row.get(6)),
                        String.valueOf(row.get(7)),
                        ((Number) row.get(8)).longValue()
                ))
                .toList();
    }

    private void removeFromGridGain(String paymentId) {
        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, MerchantPaymentAttempt> attempts = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS);
        IgniteCache<String, LedgerEntry> ledgerEntries = ignite.cache(CacheNames.LEDGER_ENTRIES);

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            payments.remove(paymentId);
            attempts.remove(paymentId);
            ledgerEntries.removeAll(ledgerEntryIds(paymentId));
            tx.commit();
        }
    }

    private Set<String> ledgerEntryIds(String paymentId) {
        List<List<?>> rows = ignite.cache(CacheNames.LEDGER_ENTRIES)
                .query(new SqlFieldsQuery(
                        "SELECT entryId FROM LedgerEntry WHERE paymentId = ?"
                ).setArgs(paymentId))
                .getAll();

        Set<String> ids = new HashSet<>(rows.size());
        for (List<?> row : rows) {
            if (!row.isEmpty() && row.get(0) != null) {
                ids.add(String.valueOf(row.get(0)));
            }
        }
        return ids;
    }
}
