package com.example.paymentsdemo.domain;

import java.io.Serializable;
import org.apache.ignite.cache.query.annotations.QuerySqlField;

public class Account implements Serializable {

    private static final long serialVersionUID = 1L;

    @QuerySqlField(index = true)
    private String accountId;

    @QuerySqlField
    private String customerName;

    @QuerySqlField
    private long availableBalanceMinor;

    @QuerySqlField
    private String currency;

    @QuerySqlField
    private AccountStatus status;

    @QuerySqlField
    private RiskTier riskTier;

    public Account() {
    }

    public Account(
            String accountId,
            String customerName,
            long availableBalanceMinor,
            String currency,
            AccountStatus status,
            RiskTier riskTier
    ) {
        this.accountId = accountId;
        this.customerName = customerName;
        this.availableBalanceMinor = availableBalanceMinor;
        this.currency = currency;
        this.status = status;
        this.riskTier = riskTier;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public long getAvailableBalanceMinor() {
        return availableBalanceMinor;
    }

    public void setAvailableBalanceMinor(long availableBalanceMinor) {
        this.availableBalanceMinor = availableBalanceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public RiskTier getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(RiskTier riskTier) {
        this.riskTier = riskTier;
    }
}
