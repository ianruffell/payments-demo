package com.example.paymentsdemo.config;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.service.CacheNames;
import jakarta.annotation.PreDestroy;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GridGainConfig {

    private Ignite ignite;

    @Bean
    public Ignite ignite(@Value("${demo.gridgain.persistence-enabled:false}") boolean persistenceEnabled) {
        IgniteConfiguration cfg = new IgniteConfiguration();
        cfg.setIgniteInstanceName("payments-demo-node");
        cfg.setPeerClassLoadingEnabled(true);
        cfg.setDiscoverySpi(discoverySpi());
        cfg.setDataStorageConfiguration(dataStorageConfiguration(persistenceEnabled));
        cfg.setCacheConfiguration(
                cacheConfiguration(CacheNames.ACCOUNTS, String.class, Account.class),
                cacheConfiguration(CacheNames.MERCHANTS, String.class, Merchant.class),
                cacheConfiguration(CacheNames.PAYMENTS, String.class, Payment.class),
                cacheConfiguration(CacheNames.LEDGER_ENTRIES, String.class, LedgerEntry.class)
        );

        ignite = Ignition.start(cfg);
        ignite.cluster().state(ClusterState.ACTIVE);
        return ignite;
    }

    @PreDestroy
    public void shutdown() {
        if (ignite != null) {
            ignite.close();
        }
    }

    private TcpDiscoverySpi discoverySpi() {
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(java.util.List.of("127.0.0.1:47500..47509"));

        TcpDiscoverySpi discoverySpi = new TcpDiscoverySpi();
        discoverySpi.setIpFinder(ipFinder);
        return discoverySpi;
    }

    private DataStorageConfiguration dataStorageConfiguration(boolean persistenceEnabled) {
        DataRegionConfiguration region = new DataRegionConfiguration();
        region.setName("paymentsRegion");
        region.setPersistenceEnabled(persistenceEnabled);
        region.setInitialSize(256L * 1024 * 1024);
        region.setMaxSize(512L * 1024 * 1024);

        DataStorageConfiguration storage = new DataStorageConfiguration();
        storage.setDefaultDataRegionConfiguration(region);
        storage.setWalSegments(8);
        storage.setWalSegmentSize(64 * 1024 * 1024);
        return storage;
    }

    private <K, V> CacheConfiguration<K, V> cacheConfiguration(
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
