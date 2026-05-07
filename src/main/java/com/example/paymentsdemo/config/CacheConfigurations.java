package com.example.paymentsdemo.config;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.MerchantPaymentAttempt;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.service.CacheNames;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CacheConfiguration;

public final class CacheConfigurations {

    private CacheConfigurations() {
    }

    public static CacheConfiguration<?, ?>[] all() {
        return new CacheConfiguration[]{
                cacheConfiguration(CacheNames.ACCOUNTS, String.class, Account.class),
                cacheConfiguration(CacheNames.MERCHANTS, String.class, Merchant.class),
                cacheConfiguration(CacheNames.PAYMENTS, String.class, Payment.class),
                cacheConfiguration(CacheNames.LEDGER_ENTRIES, String.class, LedgerEntry.class),
                cacheConfiguration(CacheNames.MERCHANT_PAYMENT_ATTEMPTS, String.class, MerchantPaymentAttempt.class)
        };
    }

    private static <K, V> CacheConfiguration<K, V> cacheConfiguration(
            String name,
            Class<K> keyClass,
            Class<V> valueClass
    ) {
        CacheConfiguration<K, V> cacheConfiguration = new CacheConfiguration<>(name);
        cacheConfiguration.setCacheMode(CacheMode.PARTITIONED);
        cacheConfiguration.setBackups(1);
        cacheConfiguration.setStatisticsEnabled(true);
        cacheConfiguration.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        cacheConfiguration.setIndexedTypes(keyClass, valueClass);
        cacheConfiguration.setSqlSchema("PUBLIC");
        return cacheConfiguration;
    }
}
