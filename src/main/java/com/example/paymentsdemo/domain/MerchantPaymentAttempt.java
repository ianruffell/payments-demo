package com.example.paymentsdemo.domain;

import java.io.Serializable;
import org.apache.ignite.cache.query.annotations.QuerySqlField;

public class MerchantPaymentAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    @QuerySqlField(index = true)
    private String paymentId;

    @QuerySqlField(index = true)
    private String merchantId;

    @QuerySqlField(index = true)
    private MerchantRequestStatus status;

    @QuerySqlField
    private String merchantUrl;

    @QuerySqlField
    private String callbackUrl;

    @QuerySqlField(index = true)
    private long requestedAtEpochMs;

    @QuerySqlField(index = true)
    private long deadlineEpochMs;

    @QuerySqlField
    private long respondedAtEpochMs;

    @QuerySqlField
    private String merchantReference;

    @QuerySqlField
    private String message;

    public MerchantPaymentAttempt() {
    }

    public MerchantPaymentAttempt(
            String paymentId,
            String merchantId,
            MerchantRequestStatus status,
            String merchantUrl,
            String callbackUrl,
            long requestedAtEpochMs,
            long deadlineEpochMs,
            long respondedAtEpochMs,
            String merchantReference,
            String message
    ) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.status = status;
        this.merchantUrl = merchantUrl;
        this.callbackUrl = callbackUrl;
        this.requestedAtEpochMs = requestedAtEpochMs;
        this.deadlineEpochMs = deadlineEpochMs;
        this.respondedAtEpochMs = respondedAtEpochMs;
        this.merchantReference = merchantReference;
        this.message = message;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public MerchantRequestStatus getStatus() {
        return status;
    }

    public void setStatus(MerchantRequestStatus status) {
        this.status = status;
    }

    public String getMerchantUrl() {
        return merchantUrl;
    }

    public void setMerchantUrl(String merchantUrl) {
        this.merchantUrl = merchantUrl;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public long getRequestedAtEpochMs() {
        return requestedAtEpochMs;
    }

    public void setRequestedAtEpochMs(long requestedAtEpochMs) {
        this.requestedAtEpochMs = requestedAtEpochMs;
    }

    public long getDeadlineEpochMs() {
        return deadlineEpochMs;
    }

    public void setDeadlineEpochMs(long deadlineEpochMs) {
        this.deadlineEpochMs = deadlineEpochMs;
    }

    public long getRespondedAtEpochMs() {
        return respondedAtEpochMs;
    }

    public void setRespondedAtEpochMs(long respondedAtEpochMs) {
        this.respondedAtEpochMs = respondedAtEpochMs;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public void setMerchantReference(String merchantReference) {
        this.merchantReference = merchantReference;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
