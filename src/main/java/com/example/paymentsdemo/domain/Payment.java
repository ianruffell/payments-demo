package com.example.paymentsdemo.domain;

import java.io.Serializable;
import org.apache.ignite.cache.query.annotations.QuerySqlField;

public class Payment implements Serializable {

    private static final long serialVersionUID = 1L;

    @QuerySqlField(index = true)
    private String paymentId;

    @QuerySqlField(index = true)
    private String accountId;

    @QuerySqlField(index = true)
    private String merchantId;

    @QuerySqlField
    private long amountMinor;

    @QuerySqlField
    private String currency;

    @QuerySqlField(index = true)
    private PaymentStatus status;

    @QuerySqlField
    private long createdAtEpochMs;

    @QuerySqlField
    private long updatedAtEpochMs;

    @QuerySqlField(index = true)
    private String declineReason;

    @QuerySqlField
    private double fraudScore;

    @QuerySqlField(index = true)
    private boolean suspicious;

    @QuerySqlField
    private long capturedAtEpochMs;

    @QuerySqlField
    private long refundedAtEpochMs;

    public Payment() {
    }

    public Payment(
            String paymentId,
            String accountId,
            String merchantId,
            long amountMinor,
            String currency,
            PaymentStatus status,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            String declineReason,
            double fraudScore,
            boolean suspicious,
            long capturedAtEpochMs,
            long refundedAtEpochMs
    ) {
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.createdAtEpochMs = createdAtEpochMs;
        this.updatedAtEpochMs = updatedAtEpochMs;
        this.declineReason = declineReason;
        this.fraudScore = fraudScore;
        this.suspicious = suspicious;
        this.capturedAtEpochMs = capturedAtEpochMs;
        this.refundedAtEpochMs = refundedAtEpochMs;
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

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public void setCreatedAtEpochMs(long createdAtEpochMs) {
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public long getUpdatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public void setUpdatedAtEpochMs(long updatedAtEpochMs) {
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public void setDeclineReason(String declineReason) {
        this.declineReason = declineReason;
    }

    public double getFraudScore() {
        return fraudScore;
    }

    public void setFraudScore(double fraudScore) {
        this.fraudScore = fraudScore;
    }

    public boolean isSuspicious() {
        return suspicious;
    }

    public void setSuspicious(boolean suspicious) {
        this.suspicious = suspicious;
    }

    public long getCapturedAtEpochMs() {
        return capturedAtEpochMs;
    }

    public void setCapturedAtEpochMs(long capturedAtEpochMs) {
        this.capturedAtEpochMs = capturedAtEpochMs;
    }

    public long getRefundedAtEpochMs() {
        return refundedAtEpochMs;
    }

    public void setRefundedAtEpochMs(long refundedAtEpochMs) {
        this.refundedAtEpochMs = refundedAtEpochMs;
    }
}
