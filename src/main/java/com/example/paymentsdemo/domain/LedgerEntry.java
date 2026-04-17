package com.example.paymentsdemo.domain;

import java.io.Serializable;
import org.apache.ignite.cache.query.annotations.QuerySqlField;

public class LedgerEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    @QuerySqlField(index = true)
    private String entryId;

    @QuerySqlField(index = true)
    private String paymentId;

    @QuerySqlField(index = true)
    private String accountId;

    @QuerySqlField(index = true)
    private String merchantId;

    @QuerySqlField
    private LedgerDirection direction;

    @QuerySqlField
    private long amountMinor;

    @QuerySqlField
    private String currency;

    @QuerySqlField
    private String entryType;

    @QuerySqlField
    private long createdAtEpochMs;

    public LedgerEntry() {
    }

    public LedgerEntry(
            String entryId,
            String paymentId,
            String accountId,
            String merchantId,
            LedgerDirection direction,
            long amountMinor,
            String currency,
            String entryType,
            long createdAtEpochMs
    ) {
        this.entryId = entryId;
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.merchantId = merchantId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.entryType = entryType;
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public void setDirection(LedgerDirection direction) {
        this.direction = direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public void setCreatedAtEpochMs(long createdAtEpochMs) {
        this.createdAtEpochMs = createdAtEpochMs;
    }
}
