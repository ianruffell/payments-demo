package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.MerchantPaymentAttempt;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.dto.MerchantAuthorizationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator")
public class MerchantDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDispatchService.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    public MerchantDispatchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void dispatch(Payment payment, Merchant merchant, MerchantPaymentAttempt attempt) {
        if (merchant.getServiceUrl() == null || merchant.getServiceUrl().isBlank()) {
            log.warn("Skipping merchant dispatch for {} because merchant {} has no service URL.", payment.getPaymentId(), merchant.getMerchantId());
            return;
        }

        MerchantAuthorizationRequest requestBody = new MerchantAuthorizationRequest(
                payment.getPaymentId(),
                payment.getAccountId(),
                payment.getMerchantId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getCreatedAtEpochMs(),
                attempt.getDeadlineEpochMs(),
                attempt.getCallbackUrl()
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(merchant.getServiceUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serialize(requestBody)))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Merchant dispatch failed for payment {}.", payment.getPaymentId(), error);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        log.warn(
                                "Merchant {} returned non-success status {} for payment {}.",
                                merchant.getMerchantId(),
                                response.statusCode(),
                                payment.getPaymentId()
                        );
                    }
                });
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize merchant request", e);
        }
    }
}
