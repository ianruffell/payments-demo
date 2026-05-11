package com.example.paymentsdemo.simulator;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.dto.AuthorizePaymentRequest;
import com.example.paymentsdemo.dto.PaymentResponse;
import com.example.paymentsdemo.service.CacheNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.Ignite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("payment-initiator")
public class PaymentSimulator {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulator.class);

    private final Ignite ignite;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String processorBaseUrl;
    private final int accountCount;
    private final int merchantCount;
    private final double targetDeclineRate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger ratePerSecond;
    private final AtomicLong generatedPayments = new AtomicLong();
    private final ScheduledExecutorService tickExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService delayedExecutor = Executors.newScheduledThreadPool(4);
    private final ExecutorService workerExecutor = Executors.newFixedThreadPool(8);
    private final ConcurrentHashMap<String, Long> pendingMerchantPayments = new ConcurrentHashMap<>();
    private final AtomicBoolean missingSeedDataWarningLogged = new AtomicBoolean(false);

    public PaymentSimulator(
            Ignite ignite,
            ObjectMapper objectMapper,
            @Value("${demo.processor.base-url:http://payments-demo-app:8080}") String processorBaseUrl,
            @Value("${demo.seed.accounts:100000}") int accountCount,
            @Value("${demo.seed.merchants:5}") int merchantCount,
            @Value("${demo.simulator.default-rate-per-second:120}") int defaultRatePerSecond,
            @Value("${demo.simulator.target-decline-rate:0.02}") double targetDeclineRate
    ) {
        this.ignite = ignite;
        this.objectMapper = objectMapper;
        this.processorBaseUrl = processorBaseUrl;
        this.accountCount = accountCount;
        this.merchantCount = merchantCount;
        this.targetDeclineRate = targetDeclineRate;
        this.ratePerSecond = new AtomicInteger(defaultRatePerSecond);
    }

    @PostConstruct
    public void startTicker() {
        log.info("Payment initiator ticker started.");
        tickExecutor.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        tickExecutor.shutdownNow();
        delayedExecutor.shutdownNow();
        workerExecutor.shutdownNow();
    }

    public void start(int newRatePerSecond) {
        ratePerSecond.set(Math.max(1, newRatePerSecond));
        running.set(true);

        log.info(
                "Payment initiator started at {} payment(s)/sec [accounts={}, merchants={}, processorBaseUrl={}]",
                ratePerSecond.get(),
                ignite.cache(CacheNames.ACCOUNTS).sizeLong(),
                ignite.cache(CacheNames.MERCHANTS).sizeLong(),
                processorBaseUrl
        );
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getRatePerSecond() {
        return ratePerSecond.get();
    }

    public long getGeneratedPayments() {
        return generatedPayments.get();
    }

    private void tick() {
        processPendingMerchantPayments();

        if (!running.get()) {
            return;
        }

        for (int i = 0; i < ratePerSecond.get(); i++) {
            workerExecutor.submit(this::generatePayment);
        }
    }

    private void generatePayment() {
        SimulatedRequest simulatedRequest = ThreadLocalRandom.current().nextDouble() < targetDeclineRate
                ? buildDeclinedRequest()
                : buildApprovedRequest();

        if (simulatedRequest == null) {
            if (missingSeedDataWarningLogged.compareAndSet(false, true)) {
                log.warn("Payment initiator could not build a request from the available account and merchant data.");
            }
            return;
        }

        String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        generatedPayments.incrementAndGet();

        PaymentResponse response;
        try {
            response = authorize(new AuthorizePaymentRequest(
                    paymentId,
                    simulatedRequest.account().getAccountId(),
                    simulatedRequest.merchant().getMerchantId(),
                    simulatedRequest.amountMinor(),
                    simulatedRequest.currency()
            ));
        } catch (RuntimeException e) {
            log.warn(
                    "Payment initiator authorization failed [paymentId={}, accountId={}, merchantId={}, amountMinor={}, currency={}]",
                    paymentId,
                    simulatedRequest.account().getAccountId(),
                    simulatedRequest.merchant().getMerchantId(),
                    simulatedRequest.amountMinor(),
                    simulatedRequest.currency(),
                    e
            );
            return;
        }

        if (response.status() == PaymentStatus.PENDING_MERCHANT) {
            pendingMerchantPayments.put(paymentId, Instant.now().toEpochMilli());
            return;
        }

        if (response.status() == PaymentStatus.AUTHORIZED && ThreadLocalRandom.current().nextDouble() < 0.84) {
            delayedExecutor.schedule(() -> captureThenMaybeRefund(paymentId), randomDelay(200, 1_200), TimeUnit.MILLISECONDS);
        }
    }

    private void processPendingMerchantPayments() {
        if (pendingMerchantPayments.isEmpty()) {
            return;
        }

        long now = Instant.now().toEpochMilli();
        for (var entry : pendingMerchantPayments.entrySet()) {
            Payment payment = (Payment) ignite.cache(CacheNames.PAYMENTS).get(entry.getKey());
            if (payment == null) {
                pendingMerchantPayments.remove(entry.getKey());
                continue;
            }

            if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
                pendingMerchantPayments.remove(entry.getKey());
                if (ThreadLocalRandom.current().nextDouble() < 0.84) {
                    delayedExecutor.schedule(() -> captureThenMaybeRefund(entry.getKey()), randomDelay(200, 1_200), TimeUnit.MILLISECONDS);
                }
                continue;
            }

            if (payment.getStatus() == PaymentStatus.DECLINED
                    || payment.getStatus() == PaymentStatus.TIMED_OUT
                    || payment.getStatus() == PaymentStatus.CAPTURED
                    || payment.getStatus() == PaymentStatus.REFUNDED
                    || now - entry.getValue() > 15_000L) {
                pendingMerchantPayments.remove(entry.getKey());
            }
        }
    }

    private void captureThenMaybeRefund(String paymentId) {
        try {
            PaymentResponse capture = post(
                    processorBaseUrl + "/api/payments/" + paymentId + "/capture",
                    "",
                    PaymentResponse.class
            );
            if (capture.status() == PaymentStatus.CAPTURED && ThreadLocalRandom.current().nextDouble() < 0.07) {
                delayedExecutor.schedule(() -> refund(paymentId), randomDelay(1_500, 6_000), TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {
            // Simulator traffic is best-effort and should not halt on race conditions.
        }
    }

    private void refund(String paymentId) {
        try {
            post(
                    processorBaseUrl + "/api/payments/" + paymentId + "/refund",
                    "",
                    PaymentResponse.class
            );
        } catch (Exception ignored) {
            // Simulator traffic is best-effort and should not halt on race conditions.
        }
    }

    private PaymentResponse authorize(AuthorizePaymentRequest request) {
        return post(
                processorBaseUrl + "/api/payments/authorize",
                serialize(request),
                PaymentResponse.class
        );
    }

    private long randomDelay(int minInclusive, int maxExclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxExclusive);
    }

    private SimulatedRequest buildApprovedRequest() {
        for (int attempt = 0; attempt < 20; attempt++) {
            Account account = randomActiveAccount();
            Merchant merchant = randomActiveMerchant();
            if (account == null || merchant == null) {
                return null;
            }

            long safeUpperBound = Math.min(
                    Math.min(account.getAvailableBalanceMinor(), merchant.getMaxAmountMinor()),
                    45_000L
            );

            if (safeUpperBound < 5_00L) {
                continue;
            }

            long amountMinor = ThreadLocalRandom.current().nextLong(5_00L, safeUpperBound + 1);
            return new SimulatedRequest(account, merchant, amountMinor, account.getCurrency());
        }

        return null;
    }

    private SimulatedRequest buildDeclinedRequest() {
        Account activeAccount = randomActiveAccount();
        Merchant activeMerchant = randomActiveMerchant();
        if (activeAccount == null || activeMerchant == null) {
            return null;
        }

        double scenario = ThreadLocalRandom.current().nextDouble();
        if (scenario < 0.45) {
            return new SimulatedRequest(
                    activeAccount,
                    activeMerchant,
                    boundedAmount(activeAccount, activeMerchant, 5_00L, 35_000L),
                    alternateCurrency(activeAccount.getCurrency())
            );
        }

        if (scenario < 0.8) {
            long amountMinor = Math.max(
                    activeAccount.getAvailableBalanceMinor() + ThreadLocalRandom.current().nextLong(1_00L, 10_000L),
                    5_00L
            );
            amountMinor = Math.min(amountMinor, activeMerchant.getMaxAmountMinor());
            return new SimulatedRequest(activeAccount, activeMerchant, amountMinor, activeAccount.getCurrency());
        }

        Account suspendedAccount = randomSuspendedAccount();
        if (suspendedAccount != null) {
            return new SimulatedRequest(
                    suspendedAccount,
                    activeMerchant,
                    boundedAmount(suspendedAccount, activeMerchant, 5_00L, 20_000L),
                    suspendedAccount.getCurrency()
            );
        }

        return new SimulatedRequest(
                activeAccount,
                activeMerchant,
                boundedAmount(activeAccount, activeMerchant, activeMerchant.getMaxAmountMinor() + 1, activeMerchant.getMaxAmountMinor() + 20_000L),
                activeAccount.getCurrency()
        );
    }

    private long boundedAmount(Account account, Merchant merchant, long minInclusive, long preferredMaxInclusive) {
        long upperBound = Math.max(minInclusive, Math.min(preferredMaxInclusive, Math.max(merchant.getMaxAmountMinor(), minInclusive)));
        long lowerBound = Math.max(1_00L, minInclusive);

        if (upperBound <= lowerBound) {
            return upperBound;
        }

        if (upperBound <= Math.min(account.getAvailableBalanceMinor(), merchant.getMaxAmountMinor())
                && lowerBound <= upperBound) {
            return ThreadLocalRandom.current().nextLong(lowerBound, upperBound + 1);
        }

        return upperBound;
    }

    private Account randomActiveAccount() {
        for (int attempt = 0; attempt < 20; attempt++) {
            Account account = randomAccount();
            if (account != null && account.getStatus() == AccountStatus.ACTIVE && account.getAvailableBalanceMinor() >= 5_00L) {
                return account;
            }
        }
        return null;
    }

    private Account randomSuspendedAccount() {
        for (int attempt = 0; attempt < 30; attempt++) {
            Account account = randomAccount();
            if (account != null && account.getStatus() != AccountStatus.ACTIVE) {
                return account;
            }
        }
        return null;
    }

    private Account randomAccount() {
        int accountIndex = ThreadLocalRandom.current().nextInt(1, accountCount + 1);
        String accountId = "ACC-%06d".formatted(accountIndex);
        return (Account) ignite.cache(CacheNames.ACCOUNTS).get(accountId);
    }

    private Merchant randomActiveMerchant() {
        for (int attempt = 0; attempt < 20; attempt++) {
            Merchant merchant = randomMerchant();
            if (merchant != null && merchant.isActive() && merchant.getMaxAmountMinor() >= 5_00L) {
                return merchant;
            }
        }
        return null;
    }

    private Merchant randomMerchant() {
        int merchantIndex = ThreadLocalRandom.current().nextInt(1, merchantCount + 1);
        String merchantId = "MER-%05d".formatted(merchantIndex);
        return (Merchant) ignite.cache(CacheNames.MERCHANTS).get(merchantId);
    }

    private String alternateCurrency(String current) {
        return switch (current) {
            case "GBP" -> "EUR";
            case "EUR" -> "USD";
            default -> "GBP";
        };
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payment initiator request", e);
        }
    }

    private <T> T post(String url, String body, Class<T> responseType) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Processor request failed with status " + response.statusCode() + ": " + response.body());
            }
            if (responseType == String.class) {
                return responseType.cast(response.body());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call payment processor", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling payment processor", e);
        }
    }

    private record SimulatedRequest(Account account, Merchant merchant, long amountMinor, String currency) {
    }
}
