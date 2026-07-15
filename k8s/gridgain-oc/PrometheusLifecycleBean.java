package com.example.gg;

import io.opencensus.exporter.stats.prometheus.PrometheusStatsCollector;
import io.prometheus.client.exporter.HTTPServer;
import org.apache.ignite.IgniteException;
import org.apache.ignite.lifecycle.LifecycleBean;
import org.apache.ignite.lifecycle.LifecycleEventType;

/**
 * Starts a Prometheus scrape endpoint on :9404 backed by the OpenCensus stats that
 * {@code OpenCensusMetricExporterSpi} publishes (spec 010). Configured as a lifecycleBean in
 * ignite-config so it runs on the GridGain server node once the node has started.
 */
public class PrometheusLifecycleBean implements LifecycleBean {

    private static final int PORT = 9404;

    private volatile HTTPServer server;

    @Override
    public void onLifecycleEvent(LifecycleEventType evt) throws IgniteException {
        if (evt == LifecycleEventType.AFTER_NODE_START && server == null) {
            try {
                PrometheusStatsCollector.createAndRegister();
                server = new HTTPServer(PORT);
                System.out.println(">>> OpenCensus Prometheus endpoint started on :" + PORT);
            }
            catch (Exception e) {
                System.out.println(">>> Failed to start OpenCensus Prometheus endpoint: " + e);
            }
        }
    }
}
