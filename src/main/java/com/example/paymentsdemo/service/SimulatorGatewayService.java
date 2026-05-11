package com.example.paymentsdemo.service;

import com.example.paymentsdemo.dto.SimulatorStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class SimulatorGatewayService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String initiatorBaseUrl;

    public SimulatorGatewayService(
            ObjectMapper objectMapper,
            @Value("${demo.initiator.base-url:http://payments-demo-initiator:8080}") String initiatorBaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.initiatorBaseUrl = initiatorBaseUrl;
    }

    public SimulatorStatusResponse start(int ratePerSecond) {
        return exchange("/api/simulator/start?ratePerSecond=" + ratePerSecond, "POST", true);
    }

    public SimulatorStatusResponse stop() {
        return exchange("/api/simulator/stop", "POST", true);
    }

    public SimulatorStatusResponse status() {
        return exchange("/api/simulator", "GET", false);
    }

    private SimulatorStatusResponse exchange(String path, String method, boolean failOnError) {
        HttpRequest.BodyPublisher bodyPublisher = "POST".equals(method)
                ? HttpRequest.BodyPublishers.ofString("")
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest request = HttpRequest.newBuilder(URI.create(initiatorBaseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, bodyPublisher)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (failOnError) {
                    throw new IllegalStateException("Payment initiator returned status " + response.statusCode() + ": " + response.body());
                }
                return fallbackStatus();
            }
            return objectMapper.readValue(response.body(), SimulatorStatusResponse.class);
        } catch (IOException e) {
            if (failOnError) {
                throw new IllegalStateException("Failed to call payment initiator", e);
            }
            return fallbackStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (failOnError) {
                throw new IllegalStateException("Interrupted while calling payment initiator", e);
            }
            return fallbackStatus();
        }
    }

    private SimulatorStatusResponse fallbackStatus() {
        return new SimulatorStatusResponse(false, 0, 0);
    }
}
