package com.example.paymentsdemo.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Common Micrometer configuration for the observability layer (spec 010).
 *
 * <p>Adds a shared {@code service} tag to every meter so infrastructure and application
 * metrics can be correlated in Grafana, and caps flow-metric tag cardinality as a guard
 * against accidental per-entity labelling.
 */
@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("service", "payments-processor");
    }

    /**
     * Defensive cap: the payments-flow meters use bounded {@code stage}/{@code outcome}/{@code reason}
     * labels by design; this limits any single meter name to a sane number of series so a
     * regression cannot blow up cardinality.
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> flowCardinalityGuard() {
        return registry -> registry.config()
                .meterFilter(MeterFilter.maximumAllowableTags(
                        "payments.decline.reasons", "reason", 25, MeterFilter.deny()));
    }
}
