package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.CustomerContext;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import java.time.Duration;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Owns the cache-only customer context (spec 011).
 *
 * <p>All reads and writes go to the GridGain {@code customer_context} cache — never the external
 * system of record. Contexts are seeded from accounts at startup and updated after every payment
 * decision; a missing context (account added after seeding, or a cleared cache) falls back to a
 * cold-start baseline and rebuilds from subsequent payments.
 */
@Service
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class CustomerContextService {

    private static final Logger log = LoggerFactory.getLogger(CustomerContextService.class);

    private final Ignite ignite;
    private final int historySize;

    public CustomerContextService(
            Ignite ignite,
            @Value("${demo.fraud.ai.history-size:20}") int historySize
    ) {
        this.ignite = ignite;
        this.historySize = historySize;
    }

    /** The customer's context, or {@code null} when none exists yet (cold start). */
    public CustomerContext find(String accountId) {
        return contextCache().get(accountId);
    }

    /**
     * Baseline context derived from the account: risk tier, home currency, a deterministic
     * tenure, and a starting typical-spend estimate proportional to the balance. Used both to
     * seed all customers at startup and as the cold-start profile for unseeded customers.
     */
    public CustomerContext baselineFor(Account account, long nowEpochMs, boolean seeded) {
        long tenureDays = 30L + Math.floorMod(account.getAccountId().hashCode(), 1471L);
        long openedEpochMs = nowEpochMs - Duration.ofDays(tenureDays).toMillis();
        double seedTypicalSpendMinor = Math.max(20_00.0, Math.min(250_00.0, account.getAvailableBalanceMinor() * 0.05));

        return new CustomerContext(
                account.getAccountId(),
                account.getRiskTier() == null ? RiskTier.MEDIUM : account.getRiskTier(),
                account.getCurrency(),
                openedEpochMs,
                seedTypicalSpendMinor,
                seeded
        );
    }

    /**
     * Record a decided payment against the customer's context and write it back to GridGain.
     * Creates the context from the account baseline when none exists (cold start).
     */
    public void recordPayment(
            Account account,
            Merchant merchant,
            long amountMinor,
            String currency,
            String outcome,
            long nowEpochMs
    ) {
        try {
            IgniteCache<String, CustomerContext> cache = contextCache();
            CustomerContext context = cache.get(account.getAccountId());
            if (context == null) {
                context = baselineFor(account, nowEpochMs, false);
            }

            context.recordPayment(
                    new CustomerContext.HistoryEntry(
                            amountMinor,
                            merchant == null ? null : merchant.getMerchantId(),
                            merchant == null ? null : merchant.getCategory(),
                            merchant == null ? null : merchant.getCountry(),
                            currency,
                            outcome,
                            nowEpochMs
                    ),
                    historySize
            );

            cache.put(account.getAccountId(), context);
        } catch (Exception e) {
            // Context maintenance must never destabilise the payment pipeline.
            log.debug("Skipped customer-context update for {}: {}", account.getAccountId(), e.toString());
        }
    }

    public long contextCount() {
        return contextCache().sizeLong();
    }

    public int getHistorySize() {
        return historySize;
    }

    private IgniteCache<String, CustomerContext> contextCache() {
        return ignite.cache(CacheNames.CUSTOMER_CONTEXT);
    }
}
