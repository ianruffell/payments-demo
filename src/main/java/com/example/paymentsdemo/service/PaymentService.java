package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.LedgerDirection;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.MerchantPaymentAttempt;
import com.example.paymentsdemo.domain.MerchantRequestStatus;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.dto.AuthorizePaymentRequest;
import com.example.paymentsdemo.dto.MerchantAuthorizationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class PaymentService {

    private final Ignite ignite;
    private final FraudService fraudService;
    private final MerchantDispatchService merchantDispatchService;
    private final OracleSystemOfRecordRepository oracleRepository;
    private final String processorCallbackUrl;
    private final long merchantTimeoutMs;

    public PaymentService(
            Ignite ignite,
            FraudService fraudService,
            MerchantDispatchService merchantDispatchService,
            OracleSystemOfRecordRepository oracleRepository,
            @Value("${demo.processor.callback-url:http://payments-demo-app:8080/api/merchant-results}") String processorCallbackUrl,
            @Value("${demo.processor.merchant-timeout-ms:10000}") long merchantTimeoutMs
    ) {
        this.ignite = ignite;
        this.fraudService = fraudService;
        this.merchantDispatchService = merchantDispatchService;
        this.oracleRepository = oracleRepository;
        this.processorCallbackUrl = processorCallbackUrl;
        this.merchantTimeoutMs = merchantTimeoutMs;
    }

    public PaymentOperationResult authorize(AuthorizePaymentRequest request) {
        Payment archived = oracleRepository.findArchivedPayment(request.paymentId());
        if (archived != null) {
            return new PaymentOperationResult(archived, "Duplicate payment id", true);
        }

        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, Account> accounts = ignite.cache(CacheNames.ACCOUNTS);
        IgniteCache<String, Merchant> merchants = ignite.cache(CacheNames.MERCHANTS);
        IgniteCache<String, MerchantPaymentAttempt> attempts = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS);

        Merchant merchantForDispatch;
        Payment payment;
        MerchantPaymentAttempt attempt = null;
        String message;

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            if (payments.containsKey(request.paymentId())) {
                Payment existing = payments.get(request.paymentId());
                tx.commit();
                return new PaymentOperationResult(existing, "Duplicate payment id", true);
            }

            Account account = loadAccount(accounts, request.accountId());
            if (account == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown accountId");
            }

            Merchant merchant = loadMerchant(merchants, request.merchantId());
            if (merchant == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown merchantId");
            }

            long now = Instant.now().toEpochMilli();
            double fraudScore = fraudService.score(account, merchant, request.amountMinor());
            String declineReason = validateAuthorization(request, account, merchant, fraudScore);
            boolean suspicious = fraudService.isSuspicious(fraudScore);

            if (declineReason == null) {
                payment = new Payment(
                        request.paymentId(),
                        request.accountId(),
                        request.merchantId(),
                        request.amountMinor(),
                        request.currency(),
                        PaymentStatus.PENDING_MERCHANT,
                        now,
                        now,
                        null,
                        fraudScore,
                        suspicious,
                        0L,
                        0L
                );

                attempt = new MerchantPaymentAttempt(
                        payment.getPaymentId(),
                        merchant.getMerchantId(),
                        MerchantRequestStatus.PENDING,
                        merchant.getServiceUrl(),
                        processorCallbackUrl,
                        now,
                        now + merchantTimeoutMs,
                        0L,
                        null,
                        "Awaiting merchant response"
                );
                attempts.put(payment.getPaymentId(), attempt);
                message = "Dispatched to merchant";
            } else {
                payment = new Payment(
                        request.paymentId(),
                        request.accountId(),
                        request.merchantId(),
                        request.amountMinor(),
                        request.currency(),
                        PaymentStatus.DECLINED,
                        now,
                        now,
                        declineReason,
                        fraudScore,
                        suspicious,
                        0L,
                        0L
                );
                message = "Payment declined";
            }

            payments.put(payment.getPaymentId(), payment);
            tx.commit();
            merchantForDispatch = merchant;
        }

        if (attempt != null) {
            merchantDispatchService.dispatch(payment, merchantForDispatch, attempt);
        }

        return new PaymentOperationResult(payment, message, false);
    }

    public PaymentOperationResult processMerchantResult(MerchantAuthorizationResult result) {
        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, MerchantPaymentAttempt> attempts = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS);
        IgniteCache<String, Account> accounts = ignite.cache(CacheNames.ACCOUNTS);
        IgniteCache<String, Merchant> merchants = ignite.cache(CacheNames.MERCHANTS);
        IgniteCache<String, LedgerEntry> ledgerEntries = ignite.cache(CacheNames.LEDGER_ENTRIES);

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            Payment payment = payments.get(result.paymentId());
            if (payment == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown paymentId");
            }

            MerchantPaymentAttempt attempt = attempts.get(result.paymentId());
            if (attempt == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown merchant attempt");
            }

            if (!attempt.getMerchantId().equals(result.merchantId())) {
                throw new ResponseStatusException(BAD_REQUEST, "Merchant result does not match payment merchant");
            }

            long now = Math.max(Instant.now().toEpochMilli(), result.respondedAtEpochMs());

            attempt.setRespondedAtEpochMs(now);
            attempt.setMerchantReference(result.merchantReference());
            attempt.setMessage(normalizeReason(result.reason(), result.approved() ? "Merchant approved" : "MERCHANT_DECLINED"));

            if (payment.getStatus() != PaymentStatus.PENDING_MERCHANT || attempt.getStatus() != MerchantRequestStatus.PENDING) {
                if (attempt.getStatus() == MerchantRequestStatus.TIMED_OUT) {
                    attempt.setStatus(MerchantRequestStatus.LATE_RESPONSE);
                }
                attempts.put(attempt.getPaymentId(), attempt);
                tx.commit();
                return new PaymentOperationResult(payment, "Ignored completed merchant response", false);
            }

            if (now > attempt.getDeadlineEpochMs()) {
                markTimedOut(payment, attempt, now);
                attempt.setStatus(MerchantRequestStatus.LATE_RESPONSE);
                attempts.put(attempt.getPaymentId(), attempt);
                payments.put(payment.getPaymentId(), payment);
                tx.commit();
                return new PaymentOperationResult(payment, "Merchant response arrived after timeout", false);
            }

            if (!result.approved()) {
                payment.setStatus(PaymentStatus.DECLINED);
                payment.setDeclineReason(normalizeReason(result.reason(), "MERCHANT_DECLINED"));
                payment.setUpdatedAtEpochMs(now);
                attempt.setStatus(MerchantRequestStatus.DECLINED);
                attempts.put(attempt.getPaymentId(), attempt);
                payments.put(payment.getPaymentId(), payment);
                tx.commit();
                return new PaymentOperationResult(payment, "Merchant declined payment", false);
            }

            Account account = loadAccount(accounts, payment.getAccountId());
            Merchant merchant = loadMerchant(merchants, payment.getMerchantId());
            String declineReason = validateApprovedMerchantResponse(payment, account, merchant);

            if (declineReason != null) {
                payment.setStatus(PaymentStatus.DECLINED);
                payment.setDeclineReason(declineReason);
                payment.setUpdatedAtEpochMs(now);
                attempt.setStatus(MerchantRequestStatus.DECLINED);
                attempt.setMessage(declineReason);
                attempts.put(attempt.getPaymentId(), attempt);
                payments.put(payment.getPaymentId(), payment);
                tx.commit();
                return new PaymentOperationResult(payment, "Payment declined after merchant approval", false);
            }

            authorizeApprovedPayment(payment, account, ignite.cache(CacheNames.ACCOUNTS), ledgerEntries, now);
            attempt.setStatus(MerchantRequestStatus.APPROVED);
            attempts.put(attempt.getPaymentId(), attempt);
            payments.put(payment.getPaymentId(), payment);
            tx.commit();
            return new PaymentOperationResult(payment, "Payment authorized", false);
        }
    }

    public void markTimedOutPayments() {
        IgniteCache<String, MerchantPaymentAttempt> attempts = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS);

        List<List<?>> timedOutIds = attempts.query(new SqlFieldsQuery(
                "SELECT paymentId FROM MerchantPaymentAttempt WHERE status = ? AND deadlineEpochMs <= ?"
        ).setArgs(MerchantRequestStatus.PENDING, Instant.now().toEpochMilli())).getAll();

        for (List<?> row : timedOutIds) {
            if (!row.isEmpty() && row.get(0) != null) {
                markTimedOutPayment(String.valueOf(row.get(0)));
            }
        }
    }

    public PaymentOperationResult capture(String paymentId) {
        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, LedgerEntry> ledgerEntries = ignite.cache(CacheNames.LEDGER_ENTRIES);

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            Payment payment = payments.get(paymentId);
            if (payment == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown paymentId");
            }

            if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
                throw new ResponseStatusException(CONFLICT, "Only authorized payments can be captured");
            }

            long now = Instant.now().toEpochMilli();
            payment.setStatus(PaymentStatus.CAPTURED);
            payment.setUpdatedAtEpochMs(now);
            payment.setCapturedAtEpochMs(now);
            payments.put(paymentId, payment);

            ledgerEntries.put(
                    UUID.randomUUID().toString(),
                    new LedgerEntry(
                            UUID.randomUUID().toString(),
                            payment.getPaymentId(),
                            payment.getAccountId(),
                            payment.getMerchantId(),
                            LedgerDirection.DEBIT,
                            payment.getAmountMinor(),
                            payment.getCurrency(),
                            "CAPTURE",
                            now
                    )
            );

            tx.commit();
            return new PaymentOperationResult(payment, "Payment captured", false);
        }
    }

    public PaymentOperationResult refund(String paymentId) {
        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, Account> accounts = ignite.cache(CacheNames.ACCOUNTS);
        IgniteCache<String, LedgerEntry> ledgerEntries = ignite.cache(CacheNames.LEDGER_ENTRIES);

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            Payment payment = payments.get(paymentId);
            if (payment == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown paymentId");
            }

            if (payment.getStatus() != PaymentStatus.CAPTURED) {
                throw new ResponseStatusException(CONFLICT, "Only captured payments can be refunded");
            }

            Account account = accounts.get(payment.getAccountId());
            if (account == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Payment account missing");
            }

            long now = Instant.now().toEpochMilli();
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setUpdatedAtEpochMs(now);
            payment.setRefundedAtEpochMs(now);
            payments.put(paymentId, payment);

            account.setAvailableBalanceMinor(account.getAvailableBalanceMinor() + payment.getAmountMinor());
            accounts.put(account.getAccountId(), account);

            ledgerEntries.put(
                    UUID.randomUUID().toString(),
                    new LedgerEntry(
                            UUID.randomUUID().toString(),
                            payment.getPaymentId(),
                            payment.getAccountId(),
                            payment.getMerchantId(),
                            LedgerDirection.CREDIT,
                            payment.getAmountMinor(),
                            payment.getCurrency(),
                            "REFUND",
                            now
                    )
            );

            tx.commit();
            return new PaymentOperationResult(payment, "Payment refunded", false);
        }
    }

    private void markTimedOutPayment(String paymentId) {
        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, MerchantPaymentAttempt> attempts = ignite.cache(CacheNames.MERCHANT_PAYMENT_ATTEMPTS);

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            Payment payment = payments.get(paymentId);
            MerchantPaymentAttempt attempt = attempts.get(paymentId);

            if (payment == null || attempt == null) {
                tx.commit();
                return;
            }

            if (payment.getStatus() != PaymentStatus.PENDING_MERCHANT || attempt.getStatus() != MerchantRequestStatus.PENDING) {
                tx.commit();
                return;
            }

            long now = Instant.now().toEpochMilli();
            markTimedOut(payment, attempt, now);
            payments.put(paymentId, payment);
            attempts.put(paymentId, attempt);
            tx.commit();
        }
    }

    private void markTimedOut(Payment payment, MerchantPaymentAttempt attempt, long now) {
        payment.setStatus(PaymentStatus.TIMED_OUT);
        payment.setDeclineReason("MERCHANT_TIMEOUT");
        payment.setUpdatedAtEpochMs(now);
        attempt.setStatus(MerchantRequestStatus.TIMED_OUT);
        attempt.setRespondedAtEpochMs(now);
        attempt.setMessage("MERCHANT_TIMEOUT");
    }

    private void authorizeApprovedPayment(
            Payment payment,
            Account account,
            IgniteCache<String, Account> accounts,
            IgniteCache<String, LedgerEntry> ledgerEntries,
            long now
    ) {
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setDeclineReason(null);
        payment.setUpdatedAtEpochMs(now);

        account.setAvailableBalanceMinor(account.getAvailableBalanceMinor() - payment.getAmountMinor());
        accounts.put(account.getAccountId(), account);

        ledgerEntries.put(
                UUID.randomUUID().toString(),
                new LedgerEntry(
                        UUID.randomUUID().toString(),
                        payment.getPaymentId(),
                        payment.getAccountId(),
                        payment.getMerchantId(),
                        LedgerDirection.DEBIT,
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        "AUTH_HOLD",
                        now
                )
        );
    }

    private String validateAuthorization(
            AuthorizePaymentRequest request,
            Account account,
            Merchant merchant,
            double fraudScore
    ) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return "ACCOUNT_NOT_ACTIVE";
        }

        if (!merchant.isActive()) {
            return "MERCHANT_INACTIVE";
        }

        if (merchant.getServiceUrl() == null || merchant.getServiceUrl().isBlank()) {
            return "MERCHANT_UNAVAILABLE";
        }

        if (!account.getCurrency().equalsIgnoreCase(request.currency())) {
            return "CURRENCY_MISMATCH";
        }

        if (request.amountMinor() > merchant.getMaxAmountMinor()) {
            return "MERCHANT_MAX_AMOUNT_EXCEEDED";
        }

        if (merchantDailyTotal(merchant.getMerchantId()) + request.amountMinor() > merchant.getDailyLimitMinor()) {
            return "MERCHANT_DAILY_LIMIT_EXCEEDED";
        }

        if (account.getAvailableBalanceMinor() < request.amountMinor()) {
            return "INSUFFICIENT_FUNDS";
        }

        if (fraudService.isFraudulent(fraudScore)) {
            return "FRAUD_SCORE_EXCEEDED";
        }

        return null;
    }

    private String validateApprovedMerchantResponse(Payment payment, Account account, Merchant merchant) {
        if (account == null) {
            return "UNKNOWN_ACCOUNT";
        }

        if (merchant == null) {
            return "UNKNOWN_MERCHANT";
        }

        if (account.getStatus() != AccountStatus.ACTIVE) {
            return "ACCOUNT_NOT_ACTIVE";
        }

        if (!merchant.isActive()) {
            return "MERCHANT_INACTIVE";
        }

        if (payment.getAmountMinor() > merchant.getMaxAmountMinor()) {
            return "MERCHANT_MAX_AMOUNT_EXCEEDED";
        }

        if (merchantDailyTotal(merchant.getMerchantId()) + payment.getAmountMinor() > merchant.getDailyLimitMinor()) {
            return "MERCHANT_DAILY_LIMIT_EXCEEDED";
        }

        if (account.getAvailableBalanceMinor() < payment.getAmountMinor()) {
            return "INSUFFICIENT_FUNDS";
        }

        return null;
    }

    private long merchantDailyTotal(String merchantId) {
        long startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        SqlFieldsQuery query = new SqlFieldsQuery(
                "SELECT COALESCE(SUM(amountMinor), 0) " +
                        "FROM Payment " +
                        "WHERE merchantId = ? " +
                        "  AND createdAtEpochMs >= ? " +
                        "  AND status IN (?, ?, ?)"
        ).setArgs(
                merchantId,
                startOfDay,
                PaymentStatus.AUTHORIZED,
                PaymentStatus.CAPTURED,
                PaymentStatus.REFUNDED
        );

        try (var cursor = ignite.cache(CacheNames.PAYMENTS).query(query)) {
            var row = cursor.getAll().stream().findFirst().orElse(null);
            long activeTotal = row == null || row.isEmpty() || row.get(0) == null ? 0L : ((Number) row.get(0)).longValue();
            return activeTotal + oracleRepository.archivedMerchantDailyTotal(merchantId, startOfDay);
        }
    }

    private String normalizeReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Account loadAccount(IgniteCache<String, Account> accounts, String accountId) {
        Account account = accounts.get(accountId);
        if (account != null) {
            return account;
        }

        account = oracleRepository.findAccount(accountId);
        if (account != null) {
            accounts.put(accountId, account);
        }
        return account;
    }

    private Merchant loadMerchant(IgniteCache<String, Merchant> merchants, String merchantId) {
        Merchant merchant = merchants.get(merchantId);
        if (merchant != null) {
            return merchant;
        }

        merchant = oracleRepository.findMerchant(merchantId);
        if (merchant != null) {
            merchants.put(merchantId, merchant);
        }
        return merchant;
    }
}
