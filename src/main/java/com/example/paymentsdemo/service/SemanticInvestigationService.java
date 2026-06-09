package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.dto.SemanticInvestigationRequest;
import com.example.paymentsdemo.dto.SemanticInvestigationResponse;
import com.example.paymentsdemo.dto.SemanticInvestigationResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class SemanticInvestigationService {

    private static final Logger log = LoggerFactory.getLogger(SemanticInvestigationService.class);
    private static final int ACTIVE_INDEX_LIMIT = 250;

    private final Ignite ignite;
    private final SystemOfRecordRepository systemOfRecordRepository;
    private final SemanticEmbeddingService embeddingService;

    public SemanticInvestigationService(
            Ignite ignite,
            SystemOfRecordRepository systemOfRecordRepository,
            SemanticEmbeddingService embeddingService
    ) {
        this.ignite = ignite;
        this.systemOfRecordRepository = systemOfRecordRepository;
        this.embeddingService = embeddingService;
    }

    public SemanticInvestigationResponse investigate(SemanticInvestigationRequest request) {
        String query = request.query().trim();
        if (!systemOfRecordRepository.supportsSemanticInvestigation()) {
            return new SemanticInvestigationResponse(
                    false,
                    "Semantic investigation is available with the MariaDB 11.8 profile.",
                    query,
                    embeddingService.modelName(),
                    0,
                    List.of()
            );
        }

        try {
            refreshRecentActivePaymentIndex();
            List<SemanticInvestigationResult> results = systemOfRecordRepository.searchSimilarPayments(
                    embeddingService.vectorJson(query),
                    request.normalizedLimit()
            );
            long indexedPayments = systemOfRecordRepository.semanticPaymentIndexCount();
            return new SemanticInvestigationResponse(
                    true,
                    results.isEmpty() ? "No indexed payments yet. Start the simulator, then search again." : "MariaDB vector search complete.",
                    query,
                    embeddingService.modelName(),
                    indexedPayments,
                    results
            );
        } catch (RuntimeException e) {
            log.warn("Semantic investigation failed.", e);
            return new SemanticInvestigationResponse(
                    false,
                    "Semantic investigation failed: " + e.getMessage(),
                    query,
                    embeddingService.modelName(),
                    0,
                    List.of()
            );
        }
    }

    public void indexArchivedPayment(Payment payment) {
        indexPaymentSnapshot(payment, "ARCHIVED");
    }

    private void refreshRecentActivePaymentIndex() {
        List<List<?>> rows = ignite.cache(CacheNames.PAYMENTS)
                .query(new SqlFieldsQuery(
                        "SELECT paymentId, accountId, merchantId, amountMinor, currency, status, " +
                                "createdAtEpochMs, updatedAtEpochMs, declineReason, fraudScore, suspicious, " +
                                "capturedAtEpochMs, refundedAtEpochMs " +
                                "FROM Payment ORDER BY createdAtEpochMs DESC LIMIT " + ACTIVE_INDEX_LIMIT
                ))
                .getAll();

        for (List<?> row : rows) {
            if (row.size() < 13) {
                continue;
            }

            Payment payment = new Payment(
                    String.valueOf(row.get(0)),
                    String.valueOf(row.get(1)),
                    String.valueOf(row.get(2)),
                    ((Number) row.get(3)).longValue(),
                    String.valueOf(row.get(4)),
                    PaymentStatus.valueOf(String.valueOf(row.get(5))),
                    ((Number) row.get(6)).longValue(),
                    ((Number) row.get(7)).longValue(),
                    row.get(8) == null ? null : String.valueOf(row.get(8)),
                    ((Number) row.get(9)).doubleValue(),
                    readBoolean(row.get(10)),
                    ((Number) row.get(11)).longValue(),
                    ((Number) row.get(12)).longValue()
            );
            indexPaymentSnapshot(payment, "ACTIVE");
        }
    }

    private void indexPaymentSnapshot(Payment payment, String source) {
        if (!systemOfRecordRepository.supportsSemanticInvestigation()) {
            return;
        }

        try {
            Merchant merchant = (Merchant) ignite.cache(CacheNames.MERCHANTS).get(payment.getMerchantId());
            Account account = (Account) ignite.cache(CacheNames.ACCOUNTS).get(payment.getAccountId());
            String summary = summarize(payment, merchant, account, source);
            systemOfRecordRepository.upsertPaymentSemanticIndex(new SemanticPaymentIndexEntry(
                    payment.getPaymentId(),
                    summary,
                    payment.getStatus(),
                    payment.getMerchantId(),
                    payment.getAmountMinor(),
                    payment.getCurrency(),
                    payment.getFraudScore(),
                    payment.isSuspicious(),
                    payment.getDeclineReason(),
                    payment.getCreatedAtEpochMs(),
                    source,
                    embeddingService.vectorJson(summary)
            ));
        } catch (RuntimeException e) {
            log.warn("Failed to index payment {} for semantic investigation.", payment.getPaymentId(), e);
        }
    }

    private String summarize(Payment payment, Merchant merchant, Account account, String source) {
        String amountBand = amountBand(payment.getAmountMinor());
        String merchantCategory = merchant == null ? "unknown category" : merchant.getCategory().toLowerCase(Locale.ROOT);
        String merchantCountry = merchant == null ? "unknown country" : merchant.getCountry();
        String merchantName = merchant == null ? payment.getMerchantId() : merchant.getName();
        String accountRisk = account == null ? "unknown risk" : account.getRiskTier().name().toLowerCase(Locale.ROOT) + " risk";
        String accountStatus = account == null ? "unknown account status" : account.getStatus().name().toLowerCase(Locale.ROOT) + " account";
        String suspicion = payment.isSuspicious() ? "suspicious high fraud score" : "not suspicious";
        String decline = payment.getDeclineReason() == null ? "no decline reason" : "decline reason " + payment.getDeclineReason();
        String statusPhrase = payment.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ');

        return "Payment %s is %s from %s. Amount %s %.2f is %s. Merchant %s (%s) is a %s merchant in %s. Account is %s and %s. Fraud score %.1f, %s, %s."
                .formatted(
                        payment.getPaymentId(),
                        statusPhrase,
                        source.toLowerCase(Locale.ROOT),
                        payment.getCurrency(),
                        payment.getAmountMinor() / 100.0,
                        amountBand,
                        payment.getMerchantId(),
                        merchantName,
                        merchantCategory,
                        merchantCountry,
                        accountRisk,
                        accountStatus,
                        payment.getFraudScore(),
                        suspicion,
                        decline
                );
    }

    private String amountBand(long amountMinor) {
        if (amountMinor >= 1_000_000) {
            return "very high value";
        }
        if (amountMinor >= 250_000) {
            return "high value";
        }
        if (amountMinor >= 50_000) {
            return "medium value";
        }
        return "low value";
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
