package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.LedgerDirection;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.dto.AuthorizePaymentRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.UUID;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class PaymentService {

    private final Ignite ignite;
    private final FraudService fraudService;

    public PaymentService(Ignite ignite, FraudService fraudService) {
        this.ignite = ignite;
        this.fraudService = fraudService;
    }

    public PaymentOperationResult authorize(AuthorizePaymentRequest request) {
        IgniteCache<String, Payment> payments = ignite.cache(CacheNames.PAYMENTS);
        IgniteCache<String, Account> accounts = ignite.cache(CacheNames.ACCOUNTS);
        IgniteCache<String, Merchant> merchants = ignite.cache(CacheNames.MERCHANTS);
        IgniteCache<String, LedgerEntry> ledgerEntries = ignite.cache(CacheNames.LEDGER_ENTRIES);

        try (Transaction tx = ignite.transactions().txStart(
                TransactionConcurrency.PESSIMISTIC,
                TransactionIsolation.REPEATABLE_READ
        )) {
            if (payments.containsKey(request.paymentId())) {
                Payment existing = payments.get(request.paymentId());
                tx.commit();
                return new PaymentOperationResult(existing, "Duplicate payment id", true);
            }

            Account account = accounts.get(request.accountId());
            if (account == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown accountId");
            }

            Merchant merchant = merchants.get(request.merchantId());
            if (merchant == null) {
                throw new ResponseStatusException(NOT_FOUND, "Unknown merchantId");
            }

            long now = Instant.now().toEpochMilli();
            double fraudScore = fraudService.score(account, merchant, request.amountMinor());
            String declineReason = validateAuthorization(request, account, merchant, fraudScore);
            PaymentStatus status = declineReason == null ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
            boolean suspicious = fraudService.isSuspicious(fraudScore);

            Payment payment = new Payment(
                    request.paymentId(),
                    request.accountId(),
                    request.merchantId(),
                    request.amountMinor(),
                    request.currency(),
                    status,
                    now,
                    now,
                    declineReason,
                    fraudScore,
                    suspicious,
                    0L,
                    0L
            );

            payments.put(payment.getPaymentId(), payment);

            if (status == PaymentStatus.AUTHORIZED) {
                account.setAvailableBalanceMinor(account.getAvailableBalanceMinor() - request.amountMinor());
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

            tx.commit();
            return new PaymentOperationResult(payment, declineReason == null ? "Payment authorized" : "Payment declined", false);
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

    private long merchantDailyTotal(String merchantId) {
        long startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        ScanQuery<String, Payment> query = new ScanQuery<>((key, payment) ->
                merchantId.equals(payment.getMerchantId())
                        && payment.getCreatedAtEpochMs() >= startOfDay
                        && payment.getStatus() != PaymentStatus.DECLINED
        );

        long total = 0L;
        try (var cursor = ignite.cache(CacheNames.PAYMENTS).query(query)) {
            Iterator<javax.cache.Cache.Entry<String, Payment>> iterator = cursor.iterator();
            while (iterator.hasNext()) {
                total += iterator.next().getValue().getAmountMinor();
            }
        }
        return total;
    }
}
