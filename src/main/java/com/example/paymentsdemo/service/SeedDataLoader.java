package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteDataStreamer;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Profile("!merchant-simulator & !payment-initiator")
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private static final List<String> CURRENCIES = List.of("GBP", "EUR", "USD");
    private static final List<String> COUNTRIES = List.of("GB", "DE", "FR", "ES", "US", "NL", "NG", "BR");
    private static final List<String> CATEGORIES = List.of(
            "GROCERY",
            "TRAVEL",
            "FUEL",
            "RETAIL",
            "DIGITAL_GOODS",
            "HEALTH",
            "GAMBLING",
            "CRYPTO"
    );

    private final Ignite ignite;
    private final boolean enabled;
    private final int accountCount;
    private final int merchantCount;
    private final String merchantServiceUrlPattern;
    private final long merchantMinDailyLimitMinor;

    public SeedDataLoader(
            Ignite ignite,
            @Value("${demo.seed.enabled:true}") boolean enabled,
            @Value("${demo.seed.accounts:100000}") int accountCount,
            @Value("${demo.seed.merchants:10}") int merchantCount,
            @Value("${demo.seed.merchant-service-url-pattern:http://merchant-%05d:8080/api/merchant/payments}") String merchantServiceUrlPattern,
            @Value("${demo.seed.merchant-min-daily-limit-minor:10000000000}") long merchantMinDailyLimitMinor
    ) {
        this.ignite = ignite;
        this.enabled = enabled;
        this.accountCount = accountCount;
        this.merchantCount = merchantCount;
        this.merchantServiceUrlPattern = merchantServiceUrlPattern;
        this.merchantMinDailyLimitMinor = merchantMinDailyLimitMinor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Seed data loading is disabled.");
            return;
        }

        long accountCacheSize = ignite.cache(CacheNames.ACCOUNTS).sizeLong();
        long merchantCacheSize = ignite.cache(CacheNames.MERCHANTS).sizeLong();

        log.info("Seed cache sizes before loading: accounts={}, merchants={}", accountCacheSize, merchantCacheSize);

        if ((accountCacheSize > 0 || merchantCacheSize > 0) && !seedDataReadable()) {
            log.warn("Existing demo seed data is not readable by this application. Resetting demo caches and reloading.");
            resetDemoCaches();
            accountCacheSize = 0;
            merchantCacheSize = 0;
        }

        if (accountCacheSize == 0) {
            loadAccounts();
        } else {
            log.info("Skipping account seed load because cache already contains {} entries.", accountCacheSize);
        }

        if (merchantCacheSize == 0) {
            loadMerchants();
        } else {
            log.info("Skipping merchant seed load because cache already contains {} entries.", merchantCacheSize);
        }

        normalizeMerchantLimits();
    }

    private boolean seedDataReadable() {
        try {
            long accountCacheSize = ignite.cache(CacheNames.ACCOUNTS).sizeLong();
            long merchantCacheSize = ignite.cache(CacheNames.MERCHANTS).sizeLong();

            if (accountCacheSize > 0 && accountCacheSize != accountCount) {
                return false;
            }

            if (merchantCacheSize > 0 && merchantCacheSize != merchantCount) {
                return false;
            }

            if (accountCacheSize > 0 && ignite.cache(CacheNames.ACCOUNTS).get("ACC-000001") == null) {
                return false;
            }

            if (merchantCacheSize > 0) {
                Merchant merchant = (Merchant) ignite.cache(CacheNames.MERCHANTS).get("MER-00001");
                if (merchant == null || merchant.getServiceUrl() == null || merchant.getServiceUrl().isBlank()) {
                    return false;
                }

                if (!merchantUrlFor(1).equals(merchant.getServiceUrl())) {
                    return false;
                }
            }

            if (merchantCount > 0 && ignite.cache(CacheNames.MERCHANTS).get("MER-%05d".formatted(merchantCount)) == null) {
                return false;
            }

            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to read existing seed data.", e);
            return false;
        }
    }

    private void resetDemoCaches() {
        ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS).removeAll();
        ignite.cache(CacheNames.PAYMENTS).removeAll();
        ignite.cache(CacheNames.LEDGER_ENTRIES).removeAll();
        ignite.cache(CacheNames.ACCOUNTS).removeAll();
        ignite.cache(CacheNames.MERCHANTS).removeAll();
    }

    private void loadAccounts() {
        log.info("Loading {} accounts into GridGain.", accountCount);

        try (IgniteDataStreamer<String, Account> streamer = ignite.dataStreamer(CacheNames.ACCOUNTS)) {
            streamer.perNodeBufferSize(4096);

            for (int i = 1; i <= accountCount; i++) {
                String accountId = "ACC-%06d".formatted(i);
                Account account = new Account(
                        accountId,
                        "Customer %06d".formatted(i),
                        ThreadLocalRandom.current().nextLong(50_00, 1_000_000),
                        randomOf(CURRENCIES),
                        ThreadLocalRandom.current().nextDouble() < 0.985 ? AccountStatus.ACTIVE : AccountStatus.SUSPENDED,
                        randomRiskTier()
                );
                streamer.addData(accountId, account);
            }
        }

        log.info("Finished loading accounts.");
    }

    private void loadMerchants() {
        log.info("Loading {} merchants into GridGain.", merchantCount);

        try (IgniteDataStreamer<String, Merchant> streamer = ignite.dataStreamer(CacheNames.MERCHANTS)) {
            streamer.perNodeBufferSize(2048);

            for (int i = 1; i <= merchantCount; i++) {
                String merchantId = "MER-%05d".formatted(i);
                Merchant merchant = new Merchant(
                        merchantId,
                        "Merchant %05d".formatted(i),
                        randomOf(CATEGORIES),
                        randomOf(COUNTRIES),
                        true,
                        ThreadLocalRandom.current().nextLong(25_00, 15_000_00),
                        ThreadLocalRandom.current().nextLong(250_000_00, 2_500_000_00L),
                        merchantUrlFor(i)
                );
                streamer.addData(merchantId, merchant);
            }
        }

        log.info("Finished loading merchants.");
    }

    private void normalizeMerchantLimits() {
        long updatedCount = 0;

        for (int i = 1; i <= merchantCount; i++) {
            String merchantId = "MER-%05d".formatted(i);
            Merchant merchant = (Merchant) ignite.cache(CacheNames.MERCHANTS).get(merchantId);
            if (merchant == null) {
                continue;
            }

            if (merchant.getDailyLimitMinor() >= merchantMinDailyLimitMinor) {
                continue;
            }

            merchant.setDailyLimitMinor(merchantMinDailyLimitMinor);
            ignite.cache(CacheNames.MERCHANTS).put(merchantId, merchant);
            updatedCount++;
        }

        if (updatedCount > 0) {
            log.info(
                    "Raised daily limits for {} merchant(s) to at least {} minor units.",
                    updatedCount,
                    merchantMinDailyLimitMinor
            );
        }
    }

    private RiskTier randomRiskTier() {
        double value = ThreadLocalRandom.current().nextDouble();
        if (value < 0.68) {
            return RiskTier.LOW;
        }
        if (value < 0.93) {
            return RiskTier.MEDIUM;
        }
        return RiskTier.HIGH;
    }

    private <T> T randomOf(List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private String merchantUrlFor(int merchantIndex) {
        return merchantServiceUrlPattern.formatted(merchantIndex);
    }
}
