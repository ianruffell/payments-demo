package com.example.paymentsdemo.domain;

import java.io.Serializable;
import org.apache.ignite.cache.query.annotations.QuerySqlField;

public class Merchant implements Serializable {

    private static final long serialVersionUID = 1L;

    @QuerySqlField(index = true)
    private String merchantId;

    @QuerySqlField
    private String name;

    @QuerySqlField(index = true)
    private String category;

    @QuerySqlField(index = true)
    private String country;

    @QuerySqlField
    private boolean active;

    @QuerySqlField
    private long maxAmountMinor;

    @QuerySqlField
    private long dailyLimitMinor;

    public Merchant() {
    }

    public Merchant(
            String merchantId,
            String name,
            String category,
            String country,
            boolean active,
            long maxAmountMinor,
            long dailyLimitMinor
    ) {
        this.merchantId = merchantId;
        this.name = name;
        this.category = category;
        this.country = country;
        this.active = active;
        this.maxAmountMinor = maxAmountMinor;
        this.dailyLimitMinor = dailyLimitMinor;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public long getMaxAmountMinor() {
        return maxAmountMinor;
    }

    public void setMaxAmountMinor(long maxAmountMinor) {
        this.maxAmountMinor = maxAmountMinor;
    }

    public long getDailyLimitMinor() {
        return dailyLimitMinor;
    }

    public void setDailyLimitMinor(long dailyLimitMinor) {
        this.dailyLimitMinor = dailyLimitMinor;
    }
}
