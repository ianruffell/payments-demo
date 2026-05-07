CREATE DATABASE IF NOT EXISTS payments_demo;
USE payments_demo;

CREATE TABLE IF NOT EXISTS accounts (
    accountId VARCHAR(32) NOT NULL,
    customerName VARCHAR(255) NOT NULL,
    availableBalanceMinor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    riskTier VARCHAR(16) NOT NULL,
    PRIMARY KEY (accountId),
    CONSTRAINT chk_accounts_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_accounts_riskTier
        CHECK (riskTier IN ('LOW', 'MEDIUM', 'HIGH'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS merchants (
    merchantId VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    country CHAR(2) NOT NULL,
    active BOOLEAN NOT NULL,
    maxAmountMinor BIGINT NOT NULL,
    dailyLimitMinor BIGINT NOT NULL,
    PRIMARY KEY (merchantId),
    KEY idx_merchants_category (category),
    KEY idx_merchants_country (country)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payments (
    paymentId VARCHAR(64) NOT NULL,
    accountId VARCHAR(32) NOT NULL,
    merchantId VARCHAR(32) NOT NULL,
    amountMinor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    createdAtEpochMs BIGINT NOT NULL,
    updatedAtEpochMs BIGINT NOT NULL,
    declineReason VARCHAR(64) NULL,
    fraudScore DOUBLE NOT NULL,
    suspicious BOOLEAN NOT NULL,
    capturedAtEpochMs BIGINT NOT NULL DEFAULT 0,
    refundedAtEpochMs BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (paymentId),
    KEY idx_payments_accountId (accountId),
    KEY idx_payments_merchantId (merchantId),
    KEY idx_payments_status (status),
    KEY idx_payments_declineReason (declineReason),
    KEY idx_payments_suspicious (suspicious),
    CONSTRAINT chk_payments_status
        CHECK (status IN ('AUTHORIZED', 'CAPTURED', 'DECLINED', 'REFUNDED')),
    CONSTRAINT fk_payments_accounts
        FOREIGN KEY (accountId) REFERENCES accounts (accountId),
    CONSTRAINT fk_payments_merchants
        FOREIGN KEY (merchantId) REFERENCES merchants (merchantId)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ledger_entries (
    entryId VARCHAR(64) NOT NULL,
    paymentId VARCHAR(64) NOT NULL,
    accountId VARCHAR(32) NOT NULL,
    merchantId VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amountMinor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    entryType VARCHAR(32) NOT NULL,
    createdAtEpochMs BIGINT NOT NULL,
    PRIMARY KEY (entryId),
    KEY idx_ledger_entries_paymentId (paymentId),
    KEY idx_ledger_entries_accountId (accountId),
    KEY idx_ledger_entries_merchantId (merchantId),
    CONSTRAINT chk_ledger_entries_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT fk_ledger_entries_payments
        FOREIGN KEY (paymentId) REFERENCES payments (paymentId),
    CONSTRAINT fk_ledger_entries_accounts
        FOREIGN KEY (accountId) REFERENCES accounts (accountId),
    CONSTRAINT fk_ledger_entries_merchants
        FOREIGN KEY (merchantId) REFERENCES merchants (merchantId)
) ENGINE=InnoDB;
