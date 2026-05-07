package com.example.paymentsdemo.config;

import jakarta.annotation.PreDestroy;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.gridgain.grid.configuration.GridGainConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!merchant-simulator")
public class GridGainConfig {

    private Ignite ignite;

    @Bean
    public Ignite ignite(
            @Value("${demo.gridgain.instance-name:payments-demo-client}") String instanceName,
            @Value("${demo.gridgain.discovery-addresses}") List<String> discoveryAddresses,
            @Value("${demo.gridgain.license-url:}") String licenseUrl
    ) {
        IgniteConfiguration cfg = new IgniteConfiguration();
        cfg.setIgniteInstanceName(instanceName);
        cfg.setClientMode(true);
        cfg.setDiscoverySpi(discoverySpi(discoveryAddresses));
        if (licenseUrl != null && !licenseUrl.isBlank()) {
            GridGainConfiguration ggCfg = new GridGainConfiguration();
            ggCfg.setLicenseUrl(licenseUrl);
            cfg.setPluginConfigurations(ggCfg);
        }

        ignite = Ignition.start(cfg);
        for (var cacheConfiguration : CacheConfigurations.all()) {
            ignite.getOrCreateCache(cacheConfiguration);
        }
        return ignite;
    }

    @PreDestroy
    public void shutdown() {
        if (ignite != null) {
            ignite.close();
        }
    }

    private TcpDiscoverySpi discoverySpi(List<String> discoveryAddresses) {
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(discoveryAddresses);

        TcpDiscoverySpi discoverySpi = new TcpDiscoverySpi();
        discoverySpi.setIpFinder(ipFinder);
        return discoverySpi;
    }
}
