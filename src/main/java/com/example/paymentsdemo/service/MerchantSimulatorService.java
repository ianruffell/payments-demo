package com.example.paymentsdemo.service;

import com.example.paymentsdemo.dto.MerchantAuthorizationRequest;
import com.example.paymentsdemo.dto.MerchantAuthorizationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@Profile("merchant-simulator")
public class MerchantSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(MerchantSimulatorService.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper objectMapper;
    private final String merchantId;
    private final String merchantName;
    private final String fallbackCallbackUrl;
    private final int minDelayMs;
    private final int maxDelayMs;
    private final int timeoutDelayMinMs;
    private final int timeoutDelayMaxMs;
    private final double timeoutProbability;
    private final double approvalRate;
    private final long declineAboveMinor;

    public MerchantSimulatorService(
            ObjectMapper objectMapper,
            @Value("${demo.merchant.id}") String merchantId,
            @Value("${demo.merchant.name}") String merchantName,
            @Value("${demo.merchant.callback-url:}") String fallbackCallbackUrl,
            @Value("${demo.merchant.min-delay-ms:350}") int minDelayMs,
            @Value("${demo.merchant.max-delay-ms:3200}") int maxDelayMs,
            @Value("${demo.merchant.timeout-delay-min-ms:11000}") int timeoutDelayMinMs,
            @Value("${demo.merchant.timeout-delay-max-ms:14000}") int timeoutDelayMaxMs,
            @Value("${demo.merchant.timeout-probability:0.03}") double timeoutProbability,
            @Value("${demo.merchant.approval-rate:0.97}") double approvalRate,
            @Value("${demo.merchant.decline-above-minor:1250000}") long declineAboveMinor
    ) {
        this.objectMapper = objectMapper;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.fallbackCallbackUrl = fallbackCallbackUrl;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.timeoutDelayMinMs = timeoutDelayMinMs;
        this.timeoutDelayMaxMs = timeoutDelayMaxMs;
        this.timeoutProbability = timeoutProbability;
        this.approvalRate = approvalRate;
        this.declineAboveMinor = declineAboveMinor;
    }

    public Map<String, Object> receivePayment(MerchantAuthorizationRequest request) {
        if (!merchantId.equals(request.merchantId())) {
            throw new ResponseStatusException(BAD_REQUEST, "Merchant ID does not match this simulator instance");
        }

        long delayMs = simulatedDelayMs();
        boolean approved = shouldApprove(request);
        String reason = approved ? "MERCHANT_APPROVED" : "MERCHANT_DECLINED";
        String merchantReference = merchantId + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        executor.schedule(
                () -> sendCallback(request, approved, reason, merchantReference),
                delayMs,
                TimeUnit.MILLISECONDS
        );

        log.info(
                "Merchant {} accepted payment {} for async processing [delayMs={}, approved={}]",
                merchantId,
                request.paymentId(),
                delayMs,
                approved
        );

        return Map.of(
                "merchantId", merchantId,
                "merchantName", merchantName,
                "paymentId", request.paymentId(),
                "accepted", true,
                "expectedDelayMs", delayMs
        );
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private long simulatedDelayMs() {
        if (ThreadLocalRandom.current().nextDouble() < timeoutProbability) {
            return ThreadLocalRandom.current().nextLong(timeoutDelayMinMs, timeoutDelayMaxMs + 1L);
        }
        return ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1L);
    }

    private boolean shouldApprove(MerchantAuthorizationRequest request) {
        if (request.amountMinor() > declineAboveMinor) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < approvalRate;
    }

    private void sendCallback(
            MerchantAuthorizationRequest request,
            boolean approved,
            String reason,
            String merchantReference
    ) {
        String callbackUrl = request.callbackUrl() == null || request.callbackUrl().isBlank()
                ? fallbackCallbackUrl
                : request.callbackUrl();

        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.warn("Merchant {} has no callback URL for payment {}.", merchantId, request.paymentId());
            return;
        }

        MerchantAuthorizationResult result = new MerchantAuthorizationResult(
                request.paymentId(),
                merchantId,
                approved,
                reason,
                merchantReference,
                Instant.now().toEpochMilli()
        );

        HttpRequest callbackRequest = HttpRequest.newBuilder(URI.create(callbackUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serialize(result)))
                .build();

        httpClient.sendAsync(callbackRequest, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Merchant {} failed to callback for payment {}.", merchantId, request.paymentId(), error);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        log.warn(
                                "Merchant {} received non-success callback response {} for payment {}.",
                                merchantId,
                                response.statusCode(),
                                request.paymentId()
                        );
                    }
                });
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize merchant callback", e);
        }
    }
}
