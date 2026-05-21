package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.MerchantPaymentAttempt;
import com.example.paymentsdemo.domain.Payment;
import java.util.List;

public interface SystemOfRecordRepository {

    void initializeSchema();

    long accountCount();

    long merchantCount();

    boolean referenceDataReadable(int expectedAccounts, int expectedMerchants, String expectedMerchantUrl);

    void resetDemoData();

    void upsertAccounts(List<Account> accounts);

    void upsertMerchants(List<Merchant> merchants);

    List<Account> loadAllAccounts();

    List<Merchant> loadAllMerchants();

    Account findAccount(String accountId);

    Merchant findMerchant(String merchantId);

    Merchant setMerchantActive(String merchantId, boolean active);

    long archivedMerchantDailyTotal(String merchantId, long startOfDayEpochMs);

    List<PaymentHistoryRow> loadRecentArchivedPayments(long windowStartEpochMs);

    Payment findArchivedPayment(String paymentId);

    boolean archiveCompletedPayment(
            Payment payment,
            MerchantPaymentAttempt attempt,
            List<LedgerEntry> ledgerEntries
    );

    void enableReferenceTableCdc();
}
