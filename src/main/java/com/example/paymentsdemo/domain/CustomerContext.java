package com.example.paymentsdemo.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cache-only behavioral context for one customer (spec 011).
 *
 * <p>Held exclusively in the GridGain {@code customer_context} cache: a baseline profile seeded
 * from the account at startup, a bounded rolling purchase history, and incrementally-maintained
 * spend statistics. It is derived state — never persisted to the external system of record and
 * rebuilt from payment activity if the cache is cleared.
 */
public class CustomerContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** One decided payment in the rolling history. */
    public static class HistoryEntry implements Serializable {

        private static final long serialVersionUID = 1L;

        private long amountMinor;
        private String merchantId;
        private String merchantCategory;
        private String merchantCountry;
        private String currency;
        private String outcome;
        private long timestampEpochMs;

        public HistoryEntry() {
        }

        public HistoryEntry(
                long amountMinor,
                String merchantId,
                String merchantCategory,
                String merchantCountry,
                String currency,
                String outcome,
                long timestampEpochMs
        ) {
            this.amountMinor = amountMinor;
            this.merchantId = merchantId;
            this.merchantCategory = merchantCategory;
            this.merchantCountry = merchantCountry;
            this.currency = currency;
            this.outcome = outcome;
            this.timestampEpochMs = timestampEpochMs;
        }

        public long getAmountMinor() {
            return amountMinor;
        }

        public String getMerchantId() {
            return merchantId;
        }

        public String getMerchantCategory() {
            return merchantCategory;
        }

        public String getMerchantCountry() {
            return merchantCountry;
        }

        public String getCurrency() {
            return currency;
        }

        public String getOutcome() {
            return outcome;
        }

        public long getTimestampEpochMs() {
            return timestampEpochMs;
        }
    }

    private String accountId;
    private RiskTier riskTier;
    private String homeCurrency;
    private long accountOpenedEpochMs;
    private boolean seeded;

    /** Starting typical-spend estimate used until real history accumulates. */
    private double seedTypicalSpendMinor;

    /** Welford running statistics over decided payment amounts. */
    private long spendCount;
    private double spendMeanMinor;
    private double spendM2;

    private List<HistoryEntry> history = new ArrayList<>();
    private long lastSeenEpochMs;

    public CustomerContext() {
    }

    public CustomerContext(
            String accountId,
            RiskTier riskTier,
            String homeCurrency,
            long accountOpenedEpochMs,
            double seedTypicalSpendMinor,
            boolean seeded
    ) {
        this.accountId = accountId;
        this.riskTier = riskTier;
        this.homeCurrency = homeCurrency;
        this.accountOpenedEpochMs = accountOpenedEpochMs;
        this.seedTypicalSpendMinor = seedTypicalSpendMinor;
        this.seeded = seeded;
    }

    public void recordPayment(HistoryEntry entry, int maxHistorySize) {
        history.add(entry);
        while (history.size() > maxHistorySize) {
            history.remove(0);
        }

        spendCount++;
        double delta = entry.getAmountMinor() - spendMeanMinor;
        spendMeanMinor += delta / spendCount;
        spendM2 += delta * (entry.getAmountMinor() - spendMeanMinor);

        lastSeenEpochMs = Math.max(lastSeenEpochMs, entry.getTimestampEpochMs());
    }

    /** Typical spend: observed mean once history exists, otherwise the seeded estimate. */
    public double typicalSpendMinor() {
        return spendCount >= 3 ? spendMeanMinor : seedTypicalSpendMinor;
    }

    /** Standard deviation of observed spend; falls back to a fraction of the typical spend. */
    public double spendStdDevMinor() {
        if (spendCount >= 3) {
            double variance = spendM2 / (spendCount - 1);
            double stdDev = Math.sqrt(Math.max(0.0, variance));
            return Math.max(stdDev, typicalSpendMinor() * 0.10);
        }
        return Math.max(1.0, seedTypicalSpendMinor * 0.75);
    }

    public int paymentsSince(long sinceEpochMs) {
        int count = 0;
        for (HistoryEntry entry : history) {
            if (entry.getTimestampEpochMs() >= sinceEpochMs) {
                count++;
            }
        }
        return count;
    }

    public int recentDeclineCount() {
        int count = 0;
        for (HistoryEntry entry : history) {
            if (entry.getOutcome() != null && entry.getOutcome().startsWith("DECLINED")) {
                count++;
            }
        }
        return count;
    }

    public boolean hasSeenMerchant(String merchantId) {
        for (HistoryEntry entry : history) {
            if (entry.getMerchantId() != null && entry.getMerchantId().equals(merchantId)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSeenCountry(String country) {
        for (HistoryEntry entry : history) {
            if (entry.getMerchantCountry() != null && entry.getMerchantCountry().equals(country)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> distinctMerchants() {
        Set<String> merchants = new HashSet<>();
        for (HistoryEntry entry : history) {
            if (entry.getMerchantId() != null) {
                merchants.add(entry.getMerchantId());
            }
        }
        return merchants;
    }

    public String getAccountId() {
        return accountId;
    }

    public RiskTier getRiskTier() {
        return riskTier;
    }

    public String getHomeCurrency() {
        return homeCurrency;
    }

    public long getAccountOpenedEpochMs() {
        return accountOpenedEpochMs;
    }

    public boolean isSeeded() {
        return seeded;
    }

    public double getSeedTypicalSpendMinor() {
        return seedTypicalSpendMinor;
    }

    public long getSpendCount() {
        return spendCount;
    }

    public List<HistoryEntry> getHistory() {
        return history;
    }

    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }
}
