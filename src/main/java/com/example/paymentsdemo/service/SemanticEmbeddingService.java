package com.example.paymentsdemo.service;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SemanticEmbeddingService {

    public static final int DIMENSIONS = 64;

    public String modelName() {
        return "local-demo-semantic-hash-v1";
    }

    public String vectorJson(String text) {
        double[] vector = embed(text);
        StringBuilder json = new StringBuilder(DIMENSIONS * 8);
        json.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        json.append(']');
        return json.toString();
    }

    private double[] embed(String text) {
        String normalized = normalize(text);
        double[] vector = new double[DIMENSIONS];

        addConceptWeights(normalized, vector);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() < 2) {
                continue;
            }
            addHashedToken(token, vector, 0.45);
            if (token.length() > 5) {
                addHashedToken(token.substring(0, Math.min(token.length(), 7)), vector, 0.2);
            }
        }

        normalizeInPlace(vector);
        return vector;
    }

    private void addConceptWeights(String text, double[] vector) {
        addIfContains(text, vector, 0, 2.6, "fraud", "suspicious", "risk", "risky", "anomaly", "unusual");
        addIfContains(text, vector, 1, 2.4, "declined", "decline", "rejected", "blocked", "failed");
        addIfContains(text, vector, 2, 2.0, "timeout", "timed out", "late", "stalled", "unresponsive");
        addIfContains(text, vector, 3, 2.0, "authorized", "approved", "success", "accepted");
        addIfContains(text, vector, 4, 1.8, "captured", "settled", "capture", "settlement");
        addIfContains(text, vector, 5, 1.8, "refund", "refunded", "credit");
        addIfContains(text, vector, 6, 2.2, "high value", "large", "expensive", "big", "above limit", "max amount");
        addIfContains(text, vector, 7, 1.6, "small", "low value", "minor");
        addIfContains(text, vector, 8, 1.8, "travel", "airline", "hotel");
        addIfContains(text, vector, 9, 1.8, "grocery", "supermarket", "food");
        addIfContains(text, vector, 10, 1.8, "fuel", "petrol", "gas");
        addIfContains(text, vector, 11, 1.8, "retail", "shop", "store");
        addIfContains(text, vector, 12, 1.8, "digital", "digital goods", "online", "software");
        addIfContains(text, vector, 13, 1.8, "gambling", "casino", "bet");
        addIfContains(text, vector, 14, 1.8, "crypto", "cryptocurrency", "exchange");
        addIfContains(text, vector, 15, 1.8, "health", "medical", "pharmacy");
        addIfContains(text, vector, 16, 2.0, "insufficient funds", "balance", "available balance");
        addIfContains(text, vector, 17, 2.0, "merchant inactive", "merchant unavailable", "outage", "inactive");
        addIfContains(text, vector, 18, 1.4, "gbp", "pound", "sterling");
        addIfContains(text, vector, 19, 1.4, "eur", "euro");
        addIfContains(text, vector, 20, 1.4, "usd", "dollar");
        addIfContains(text, vector, 21, 1.2, "active payment", "gridgain", "hot state");
        addIfContains(text, vector, 22, 1.2, "archived", "history", "system of record");
        addIfContains(text, vector, 23, 1.4, "pending", "merchant review", "awaiting merchant");
    }

    private void addIfContains(String text, double[] vector, int dimension, double weight, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                vector[dimension] += weight;
                return;
            }
        }
    }

    private void addHashedToken(String token, double[] vector, double weight) {
        int hash = murmurish(token);
        int first = Math.floorMod(hash, DIMENSIONS);
        int second = Math.floorMod(hash >>> 8, DIMENSIONS);
        vector[first] += weight;
        vector[second] += weight * 0.5;
    }

    private int murmurish(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        int hash = 0x9747b28c;
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x5bd1e995;
            hash ^= hash >>> 15;
        }
        return hash;
    }

    private void normalizeInPlace(double[] vector) {
        double magnitude = 0;
        for (double value : vector) {
            magnitude += value * value;
        }

        if (magnitude == 0) {
            vector[DIMENSIONS - 1] = 1.0;
            return;
        }

        double scale = 1.0 / Math.sqrt(magnitude);
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
    }

    private String normalize(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT);
    }
}
