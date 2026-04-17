package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteDataStreamer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SeedDataLoader implements ApplicationRunner {

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
    private final int accountCount;
    private final int merchantCount;

    public SeedDataLoader(
            Ignite ignite,
            @Value("${demo.seed.accounts:100000}") int accountCount,
            @Value("${demo.seed.merchants:10000}") int merchantCount
    ) {
        this.ignite = ignite;
        this.accountCount = accountCount;
        this.merchantCount = merchantCount;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ignite.cache(CacheNames.ACCOUNTS).sizeLong() > 0 || ignite.cache(CacheNames.MERCHANTS).sizeLong() > 0) {
            return;
        }

        loadAccounts();
        loadMerchants();
    }

    private void loadAccounts() {
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
    }

    private void loadMerchants() {
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
                        ThreadLocalRandom.current().nextLong(250_000_00, 2_500_000_00L)
                );
                streamer.addData(merchantId, merchant);
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
}
