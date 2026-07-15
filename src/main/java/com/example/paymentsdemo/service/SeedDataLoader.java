package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.CustomerContext;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private static final List<String> CURRENCIES = List.of("GBP", "EUR", "USD");
    private static final List<String> COUNTRIES = List.of("GB", "DE", "FR", "ES", "US", "NL", "NG", "BR");
    private static final Pattern GENERIC_MERCHANT_NAME = Pattern.compile("Merchant \\d{5}");
    private static final Map<String, List<String>> MERCHANT_NAMES_BY_CATEGORY = Map.of(
            "GROCERY", List.of("Green Basket Market", "Pantry Harbor Foods"),
            "TRAVEL", List.of("Skytrail Journeys", "Roamline Travel"),
            "FUEL", List.of("Octane Bay Fuel", "Pump & Pilot"),
            "RETAIL", List.of("Elm & Alloy Outfitters", "Cornerstone Goods"),
            "DIGITAL_GOODS", List.of("Pixel Grove Downloads", "Cloudcrate Digital"),
            "HEALTH", List.of("Vital Spring Pharmacy", "Carewell Clinic"),
            "GAMBLING", List.of("Lucky Lantern Casino", "Jackpot Junction"),
            "CRYPTO", List.of("Chainvault Exchange", "Blockhaven Markets")
    );
    private static final List<MerchantProfile> MERCHANT_PROFILES = List.of(
            new MerchantProfile("Skytrail Journeys", "TRAVEL"),
            new MerchantProfile("Jackpot Junction", "GAMBLING"),
            new MerchantProfile("Green Basket Market", "GROCERY"),
            new MerchantProfile("Cornerstone Goods", "RETAIL"),
            new MerchantProfile("Lucky Lantern Casino", "GAMBLING"),
            new MerchantProfile("Pixel Grove Downloads", "DIGITAL_GOODS"),
            new MerchantProfile("Vital Spring Pharmacy", "HEALTH"),
            new MerchantProfile("Octane Bay Fuel", "FUEL"),
            new MerchantProfile("Chainvault Exchange", "CRYPTO")
    );

    private final Ignite ignite;
    private final SystemOfRecordRepository systemOfRecordRepository;
    private final CustomerContextService customerContextService;
    private final boolean enabled;
    private final int accountCount;
    private final int merchantCount;
    private final String merchantServiceUrlPattern;
    private final long merchantMinDailyLimitMinor;

    public SeedDataLoader(
            Ignite ignite,
            SystemOfRecordRepository systemOfRecordRepository,
            CustomerContextService customerContextService,
            @Value("${demo.seed.enabled:true}") boolean enabled,
            @Value("${demo.seed.accounts:100000}") int accountCount,
            @Value("${demo.seed.merchants:5}") int merchantCount,
            @Value("${demo.seed.merchant-service-url-pattern:http://merchant-%05d:8080/api/merchant/payments}") String merchantServiceUrlPattern,
            @Value("${demo.seed.merchant-min-daily-limit-minor:10000000000}") long merchantMinDailyLimitMinor
    ) {
        this.ignite = ignite;
        this.systemOfRecordRepository = systemOfRecordRepository;
        this.customerContextService = customerContextService;
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

        systemOfRecordRepository.initializeSchema();

        long systemOfRecordAccounts = systemOfRecordRepository.accountCount();
        long systemOfRecordMerchants = systemOfRecordRepository.merchantCount();
        log.info(
                "Seed store sizes before loading: systemOfRecordAccounts={}, systemOfRecordMerchants={}",
                systemOfRecordAccounts,
                systemOfRecordMerchants
        );

        if ((systemOfRecordAccounts > 0 || systemOfRecordMerchants > 0) && !seedDataReadable()) {
            log.warn("Existing demo seed data is not readable by this application. Resetting demo caches and reloading.");
            resetDemoStores();
            systemOfRecordAccounts = 0;
            systemOfRecordMerchants = 0;
        }

        if (systemOfRecordAccounts == 0) {
            loadAccounts();
        } else {
            log.info("Skipping account seed load because system of record already contains {} entries.", systemOfRecordAccounts);
        }

        if (systemOfRecordMerchants == 0) {
            loadMerchants();
        } else {
            log.info("Skipping merchant seed load because system of record already contains {} entries.", systemOfRecordMerchants);
        }

        normalizeMerchantLimits();
        normalizeGenericMerchantNames();
        systemOfRecordRepository.enableReferenceTableCdc();
        refreshReferenceCaches();
        seedCustomerContexts();
    }

    /**
     * Give every customer an initial context in GridGain at startup (spec 011) so the AI fraud
     * gate has personalized input from their first payment. Cache-only: never written to the
     * external system of record.
     */
    private void seedCustomerContexts() {
        IgniteCache<String, CustomerContext> contexts = ignite.cache(CacheNames.CUSTOMER_CONTEXT);
        long existing = contexts.sizeLong();
        if (existing > 0) {
            log.info("Skipping customer-context seed because {} context(s) already exist.", existing);
            return;
        }

        long now = System.currentTimeMillis();
        long seededCount = 0;
        try (IgniteDataStreamer<String, CustomerContext> streamer = ignite.dataStreamer(CacheNames.CUSTOMER_CONTEXT)) {
            streamer.perNodeBufferSize(4096);
            for (Account account : systemOfRecordRepository.loadAllAccounts()) {
                streamer.addData(account.getAccountId(), customerContextService.baselineFor(account, now, true));
                seededCount++;
            }
        }

        log.info("Seeded initial customer contexts for {} account(s) into GridGain.", seededCount);
    }

    private boolean seedDataReadable() {
        try {
            return systemOfRecordRepository.referenceDataReadable(accountCount, merchantCount, merchantUrlFor(1));
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
        systemOfRecordRepository.resetDemoData();
    }

    private void loadAccounts() {
        log.info("Loading {} accounts into the system of record.", accountCount);

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
                systemOfRecordRepository.upsertAccounts(batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            systemOfRecordRepository.upsertAccounts(batch);
        }

        log.info("Finished loading accounts.");
    }

    private void loadMerchants() {
        log.info("Loading {} merchants into the system of record.", merchantCount);

        List<Merchant> batch = new ArrayList<>(250);
        for (int i = 1; i <= merchantCount; i++) {
            String merchantId = "MER-%05d".formatted(i);
            MerchantProfile profile = merchantProfileFor(i);
            Merchant merchant = new Merchant(
                    merchantId,
                    profile.name(),
                    profile.category(),
                    randomOf(COUNTRIES),
                    true,
                    ThreadLocalRandom.current().nextLong(25_00, 15_000_00),
                    ThreadLocalRandom.current().nextLong(250_000_00, 2_500_000_00L),
                    merchantUrlFor(i)
            );
            batch.add(merchant);
            if (batch.size() == 250) {
                systemOfRecordRepository.upsertMerchants(batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            systemOfRecordRepository.upsertMerchants(batch);
        }

        log.info("Finished loading merchants.");
    }

    private void normalizeMerchantLimits() {
        List<Merchant> merchants = systemOfRecordRepository.loadAllMerchants();
        List<Merchant> updated = new ArrayList<>();

        for (Merchant merchant : merchants) {
            if (merchant.getDailyLimitMinor() >= merchantMinDailyLimitMinor) {
                continue;
            }

            merchant.setDailyLimitMinor(merchantMinDailyLimitMinor);
            updated.add(merchant);
        }

        if (!updated.isEmpty()) {
            systemOfRecordRepository.upsertMerchants(updated);
            log.info(
                    "Raised daily limits for {} merchant(s) to at least {} minor units.",
                    updated.size(),
                    merchantMinDailyLimitMinor
            );
        }
    }

    private void normalizeGenericMerchantNames() {
        List<Merchant> merchants = systemOfRecordRepository.loadAllMerchants();
        List<Merchant> updated = new ArrayList<>();

        for (Merchant merchant : merchants) {
            if (!isGenericMerchantName(merchant.getName())) {
                continue;
            }

            String categoryName = merchantNameForCategory(merchant.getCategory(), merchant.getMerchantId());
            if (categoryName.equals(merchant.getName())) {
                continue;
            }

            merchant.setName(categoryName);
            updated.add(merchant);
        }

        if (!updated.isEmpty()) {
            systemOfRecordRepository.upsertMerchants(updated);
            log.info("Renamed {} generic merchant(s) with category-specific names.", updated.size());
        }
    }

    private void refreshReferenceCaches() {
        ignite.cache(CacheNames.ACCOUNTS).removeAll();
        ignite.cache(CacheNames.MERCHANTS).removeAll();

        try (IgniteDataStreamer<String, Account> accountStreamer = ignite.dataStreamer(CacheNames.ACCOUNTS);
             IgniteDataStreamer<String, Merchant> merchantStreamer = ignite.dataStreamer(CacheNames.MERCHANTS)) {
            accountStreamer.perNodeBufferSize(4096);
            merchantStreamer.perNodeBufferSize(2048);

            for (Account account : systemOfRecordRepository.loadAllAccounts()) {
                accountStreamer.addData(account.getAccountId(), account);
            }

            for (Merchant merchant : systemOfRecordRepository.loadAllMerchants()) {
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

    private MerchantProfile merchantProfileFor(int merchantIndex) {
        MerchantProfile profile = MERCHANT_PROFILES.get(Math.floorMod(merchantIndex - 1, MERCHANT_PROFILES.size()));
        if (merchantIndex <= MERCHANT_PROFILES.size()) {
            return profile;
        }

        int sequence = ((merchantIndex - 1) / MERCHANT_PROFILES.size()) + 1;
        return new MerchantProfile("%s %d".formatted(profile.name(), sequence), profile.category());
    }

    private boolean isGenericMerchantName(String name) {
        return name != null && GENERIC_MERCHANT_NAME.matcher(name).matches();
    }

    private String merchantNameForCategory(String category, String merchantId) {
        List<String> names = MERCHANT_NAMES_BY_CATEGORY.get(category);
        if (names == null || names.isEmpty()) {
            return merchantId;
        }

        int merchantIndex = merchantIndex(merchantId);
        return names.get(Math.floorMod(merchantIndex - 1, names.size()));
    }

    private int merchantIndex(String merchantId) {
        if (merchantId != null && merchantId.startsWith("MER-")) {
            try {
                return Integer.parseInt(merchantId.substring(4));
            } catch (NumberFormatException e) {
                log.debug("Could not parse merchant index from {}.", merchantId);
            }
        }

        return 1;
    }

    private String merchantUrlFor(int merchantIndex) {
        return merchantServiceUrlPattern.formatted(merchantIndex);
    }

    private record MerchantProfile(String name, String category) {
    }
}
