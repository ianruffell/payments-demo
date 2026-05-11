package com.example.paymentsdemo.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class MerchantTimeoutMonitor {

    private final PaymentService paymentService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public MerchantTimeoutMonitor(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostConstruct
    public void start() {
        executor.scheduleAtFixedRate(paymentService::markTimedOutPayments, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
