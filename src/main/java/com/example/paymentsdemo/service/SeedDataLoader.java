package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import java.util.ArrayList;
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
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
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
    private final OracleSystemOfRecordRepository oracleRepository;
    private final boolean enabled;
    private final int accountCount;
    private final int merchantCount;
    private final String merchantServiceUrlPattern;
    private final long merchantMinDailyLimitMinor;

    public SeedDataLoader(
            Ignite ignite,
            OracleSystemOfRecordRepository oracleRepository,
            @Value("${demo.seed.enabled:true}") boolean enabled,
            @Value("${demo.seed.accounts:100000}") int accountCount,
            @Value("${demo.seed.merchants:5}") int merchantCount,
            @Value("${demo.seed.merchant-service-url-pattern:http://merchant-%05d:8080/api/merchant/payments}") String merchantServiceUrlPattern,
            @Value("${demo.seed.merchant-min-daily-limit-minor:10000000000}") long merchantMinDailyLimitMinor
    ) {
        this.ignite = ignite;
        this.oracleRepository = oracleRepository;
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

        oracleRepository.initializeSchema();

        long oracleAccounts = oracleRepository.accountCount();
        long oracleMerchants = oracleRepository.merchantCount();
        log.info("Seed store sizes before loading: oracleAccounts={}, oracleMerchants={}", oracleAccounts, oracleMerchants);

        if ((oracleAccounts > 0 || oracleMerchants > 0) && !seedDataReadable()) {
            log.warn("Existing demo seed data is not readable by this application. Resetting demo caches and reloading.");
            resetDemoStores();
            oracleAccounts = 0;
            oracleMerchants = 0;
        }

        if (oracleAccounts == 0) {
            loadAccounts();
        } else {
            log.info("Skipping account seed load because Oracle already contains {} entries.", oracleAccounts);
        }

        if (oracleMerchants == 0) {
            loadMerchants();
        } else {
            log.info("Skipping merchant seed load because Oracle already contains {} entries.", oracleMerchants);
        }

        normalizeMerchantLimits();
        oracleRepository.enableReferenceTableCdc();
        refreshReferenceCaches();
    }

    private boolean seedDataReadable() {
        try {
            return oracleRepository.referenceDataReadable(accountCount, merchantCount, merchantUrlFor(1));
        } catch (RuntimeException e) {
            log.warn("Failed to read existing seed data.", e);
            return false;
        }
    }

    private void resetDemoStores() {
        ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS).removeAll();
        ignite.cache(CacheNames.PAYMENTS).removeAll();
        ignite.cache(CacheNames.LEDGER_ENTRIES).removeAll();
        ignite.cache(CacheNames.ACCOUNTS).removeAll();
        ignite.cache(CacheNames.MERCHANTS).removeAll();
        oracleRepository.resetDemoData();
    }

    private void loadAccounts() {
        log.info("Loading {} accounts into Oracle.", accountCount);

        List<Account> batch = new ArrayList<>(1000);
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
            batch.add(account);
            if (batch.size() == 1000) {
                oracleRepository.upsertAccounts(batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            oracleRepository.upsertAccounts(batch);
        }

        log.info("Finished loading accounts.");
    }

    private void loadMerchants() {
        log.info("Loading {} merchants into Oracle.", merchantCount);

        List<Merchant> batch = new ArrayList<>(250);
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
            batch.add(merchant);
            if (batch.size() == 250) {
                oracleRepository.upsertMerchants(batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            oracleRepository.upsertMerchants(batch);
        }

        log.info("Finished loading merchants.");
    }

    private void normalizeMerchantLimits() {
        List<Merchant> merchants = oracleRepository.loadAllMerchants();
        List<Merchant> updated = new ArrayList<>();

        for (Merchant merchant : merchants) {
            if (merchant.getDailyLimitMinor() >= merchantMinDailyLimitMinor) {
                continue;
            }

            merchant.setDailyLimitMinor(merchantMinDailyLimitMinor);
            updated.add(merchant);
        }

        if (!updated.isEmpty()) {
            oracleRepository.upsertMerchants(updated);
            log.info(
                    "Raised daily limits for {} merchant(s) to at least {} minor units.",
                    updated.size(),
                    merchantMinDailyLimitMinor
            );
        }
    }

    private void refreshReferenceCaches() {
        ignite.cache(CacheNames.ACCOUNTS).removeAll();
        ignite.cache(CacheNames.MERCHANTS).removeAll();

        try (IgniteDataStreamer<String, Account> accountStreamer = ignite.dataStreamer(CacheNames.ACCOUNTS);
             IgniteDataStreamer<String, Merchant> merchantStreamer = ignite.dataStreamer(CacheNames.MERCHANTS)) {
            accountStreamer.perNodeBufferSize(4096);
            merchantStreamer.perNodeBufferSize(2048);

            for (Account account : oracleRepository.loadAllAccounts()) {
                accountStreamer.addData(account.getAccountId(), account);
            }

            for (Merchant merchant : oracleRepository.loadAllMerchants()) {
                merchantStreamer.addData(merchant.getMerchantId(), merchant);
            }
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
