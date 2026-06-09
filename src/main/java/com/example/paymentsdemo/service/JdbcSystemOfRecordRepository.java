package com.example.paymentsdemo.service;

import com.example.paymentsdemo.config.ExternalDatabaseType;
import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.MerchantPaymentAttempt;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.domain.RiskTier;
import com.example.paymentsdemo.dto.SemanticInvestigationResult;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class JdbcSystemOfRecordRepository implements SystemOfRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcSystemOfRecordRepository.class);

    private static final RowMapper<Account> ACCOUNT_ROW_MAPPER = (rs, ignored) -> new Account(
            rs.getString("account_id"),
            rs.getString("customer_name"),
            rs.getLong("available_balance_minor"),
            rs.getString("currency"),
            AccountStatus.valueOf(rs.getString("status")),
            RiskTier.valueOf(rs.getString("risk_tier"))
    );

    private static final RowMapper<Merchant> MERCHANT_ROW_MAPPER = (rs, ignored) -> new Merchant(
            rs.getString("merchant_id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("country"),
            rs.getInt("active") == 1,
            rs.getLong("max_amount_minor"),
            rs.getLong("daily_limit_minor"),
            rs.getString("service_url")
    );

    private static final RowMapper<Payment> ARCHIVED_PAYMENT_ROW_MAPPER = (rs, ignored) -> new Payment(
            rs.getString("payment_id"),
            rs.getString("account_id"),
            rs.getString("merchant_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_epoch_ms"),
            rs.getLong("updated_at_epoch_ms"),
            rs.getString("decline_reason"),
            rs.getDouble("fraud_score"),
            rs.getInt("suspicious") == 1,
            rs.getLong("captured_at_epoch_ms"),
            rs.getLong("refunded_at_epoch_ms")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ExternalDatabaseType databaseType;
    private volatile boolean semanticIndexSchemaChecked;

    public JdbcSystemOfRecordRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${demo.external-db.type:oracle}") String databaseType
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseType = ExternalDatabaseType.from(databaseType);
    }

    @Override
    public void initializeSchema() {
        executeDdl(ddl("""
                CREATE TABLE ACCOUNTS (
                    ACCOUNT_ID $VARCHAR(64) PRIMARY KEY,
                    CUSTOMER_NAME $VARCHAR(255) NOT NULL,
                    AVAILABLE_BALANCE_MINOR $LONG NOT NULL,
                    CURRENCY $VARCHAR(8) NOT NULL,
                    STATUS $VARCHAR(32) NOT NULL,
                    RISK_TIER $VARCHAR(32) NOT NULL
                )
                """));

        executeDdl(ddl("""
                CREATE TABLE MERCHANTS (
                    MERCHANT_ID $VARCHAR(64) PRIMARY KEY,
                    NAME $VARCHAR(255) NOT NULL,
                    CATEGORY $VARCHAR(64) NOT NULL,
                    COUNTRY $VARCHAR(8) NOT NULL,
                    ACTIVE $BOOLEAN NOT NULL,
                    MAX_AMOUNT_MINOR $LONG NOT NULL,
                    DAILY_LIMIT_MINOR $LONG NOT NULL,
                    SERVICE_URL $VARCHAR(512) NOT NULL
                )
                """));

        executeDdl(ddl("""
                CREATE TABLE PAYMENT_ARCHIVE (
                    PAYMENT_ID $VARCHAR(64) PRIMARY KEY,
                    ACCOUNT_ID $VARCHAR(64) NOT NULL,
                    MERCHANT_ID $VARCHAR(64) NOT NULL,
                    AMOUNT_MINOR $LONG NOT NULL,
                    CURRENCY $VARCHAR(8) NOT NULL,
                    STATUS $VARCHAR(32) NOT NULL,
                    CREATED_AT_EPOCH_MS $LONG NOT NULL,
                    UPDATED_AT_EPOCH_MS $LONG NOT NULL,
                    DECLINE_REASON $VARCHAR(128),
                    FRAUD_SCORE $DECIMAL NOT NULL,
                    SUSPICIOUS $BOOLEAN NOT NULL,
                    CAPTURED_AT_EPOCH_MS $LONG NOT NULL,
                    REFUNDED_AT_EPOCH_MS $LONG NOT NULL,
                    MERCHANT_ATTEMPTED $BOOLEAN NOT NULL,
                    MERCHANT_STATUS $VARCHAR(32),
                    MERCHANT_REQUESTED_AT_EPOCH_MS $LONG NOT NULL,
                    MERCHANT_DEADLINE_EPOCH_MS $LONG NOT NULL,
                    MERCHANT_RESPONDED_AT_EPOCH_MS $LONG NOT NULL,
                    MERCHANT_REFERENCE $VARCHAR(255),
                    MERCHANT_MESSAGE $VARCHAR(255),
                    ARCHIVED_AT_EPOCH_MS $LONG NOT NULL
                )
                """));

        executeDdl(ddl("""
                CREATE TABLE LEDGER_ENTRY_ARCHIVE (
                    ENTRY_ID $VARCHAR(64) PRIMARY KEY,
                    PAYMENT_ID $VARCHAR(64) NOT NULL,
                    ACCOUNT_ID $VARCHAR(64) NOT NULL,
                    MERCHANT_ID $VARCHAR(64) NOT NULL,
                    DIRECTION $VARCHAR(16) NOT NULL,
                    AMOUNT_MINOR $LONG NOT NULL,
                    CURRENCY $VARCHAR(8) NOT NULL,
                    ENTRY_TYPE $VARCHAR(32) NOT NULL,
                    CREATED_AT_EPOCH_MS $LONG NOT NULL
                )
                """));

        ensureSemanticIndexTable();
    }

    @Override
    public long accountCount() {
        return queryForLong("SELECT COUNT(*) FROM ACCOUNTS");
    }

    @Override
    public long merchantCount() {
        return queryForLong("SELECT COUNT(*) FROM MERCHANTS");
    }

    @Override
    public boolean referenceDataReadable(int expectedAccounts, int expectedMerchants, String expectedMerchantUrl) {
        if (accountCount() != expectedAccounts || merchantCount() != expectedMerchants) {
            return false;
        }

        Account account = findAccount("ACC-000001");
        if (account == null) {
            return false;
        }

        Merchant firstMerchant = findMerchant("MER-00001");
        if (firstMerchant == null || !expectedMerchantUrl.equals(firstMerchant.getServiceUrl())) {
            return false;
        }

        return findMerchant("MER-%05d".formatted(expectedMerchants)) != null;
    }

    @Transactional
    @Override
    public void resetDemoData() {
        if (databaseType == ExternalDatabaseType.MARIADB) {
            ensureSemanticIndexTable();
            jdbcTemplate.update("DELETE FROM PAYMENT_SEMANTIC_INDEX");
        }
        jdbcTemplate.update("DELETE FROM LEDGER_ENTRY_ARCHIVE");
        jdbcTemplate.update("DELETE FROM PAYMENT_ARCHIVE");
        jdbcTemplate.update("DELETE FROM MERCHANTS");
        jdbcTemplate.update("DELETE FROM ACCOUNTS");
    }

    @Transactional
    @Override
    public void upsertAccounts(List<Account> accounts) {
        if (accounts.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                accountUpsertSql(),
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Account account = accounts.get(index);
                        ps.setString(1, account.getAccountId());
                        ps.setString(2, account.getCustomerName());
                        ps.setLong(3, account.getAvailableBalanceMinor());
                        ps.setString(4, account.getCurrency());
                        ps.setString(5, account.getStatus().name());
                        ps.setString(6, account.getRiskTier().name());
                    }

                    @Override
                    public int getBatchSize() {
                        return accounts.size();
                    }
                }
        );
    }

    @Transactional
    @Override
    public void upsertMerchants(List<Merchant> merchants) {
        if (merchants.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                merchantUpsertSql(),
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Merchant merchant = merchants.get(index);
                        ps.setString(1, merchant.getMerchantId());
                        ps.setString(2, merchant.getName());
                        ps.setString(3, merchant.getCategory());
                        ps.setString(4, merchant.getCountry());
                        ps.setInt(5, merchant.isActive() ? 1 : 0);
                        ps.setLong(6, merchant.getMaxAmountMinor());
                        ps.setLong(7, merchant.getDailyLimitMinor());
                        ps.setString(8, merchant.getServiceUrl());
                    }

                    @Override
                    public int getBatchSize() {
                        return merchants.size();
                    }
                }
        );
    }

    @Override
    public List<Account> loadAllAccounts() {
        return jdbcTemplate.query(
                "SELECT account_id, customer_name, available_balance_minor, currency, status, risk_tier FROM ACCOUNTS",
                ACCOUNT_ROW_MAPPER
        );
    }

    @Override
    public List<Merchant> loadAllMerchants() {
        return jdbcTemplate.query(
                "SELECT merchant_id, name, category, country, active, max_amount_minor, daily_limit_minor, service_url FROM MERCHANTS",
                MERCHANT_ROW_MAPPER
        );
    }

    @Override
    public Account findAccount(String accountId) {
        return queryForObjectOrNull(
                "SELECT account_id, customer_name, available_balance_minor, currency, status, risk_tier FROM ACCOUNTS WHERE account_id = ?",
                ACCOUNT_ROW_MAPPER,
                accountId
        );
    }

    @Override
    public Merchant findMerchant(String merchantId) {
        return queryForObjectOrNull(
                "SELECT merchant_id, name, category, country, active, max_amount_minor, daily_limit_minor, service_url FROM MERCHANTS WHERE merchant_id = ?",
                MERCHANT_ROW_MAPPER,
                merchantId
        );
    }

    @Transactional
    @Override
    public Merchant setMerchantActive(String merchantId, boolean active) {
        int updated = jdbcTemplate.update(
                "UPDATE MERCHANTS SET active = ? WHERE merchant_id = ?",
                active ? 1 : 0,
                merchantId
        );
        if (updated == 0) {
            return null;
        }
        return findMerchant(merchantId);
    }

    @Override
    public long archivedMerchantDailyTotal(String merchantId, long startOfDayEpochMs) {
        return queryForLong(
                """
                SELECT COALESCE(SUM(amount_minor), 0)
                FROM PAYMENT_ARCHIVE
                WHERE merchant_id = ?
                  AND created_at_epoch_ms >= ?
                  AND status IN (?, ?)
                """,
                merchantId,
                startOfDayEpochMs,
                PaymentStatus.CAPTURED.name(),
                PaymentStatus.REFUNDED.name()
        );
    }

    @Override
    public List<PaymentHistoryRow> loadRecentArchivedPayments(long windowStartEpochMs) {
        return jdbcTemplate.query(
                """
                SELECT payment_id,
                       merchant_id,
                       amount_minor,
                       status,
                       decline_reason,
                       fraud_score,
                       suspicious,
                       created_at_epoch_ms,
                       merchant_attempted
                FROM PAYMENT_ARCHIVE
                WHERE created_at_epoch_ms >= ?
                """,
                (rs, ignored) -> new PaymentHistoryRow(
                        rs.getString("payment_id"),
                        rs.getString("merchant_id"),
                        rs.getLong("amount_minor"),
                        PaymentStatus.valueOf(rs.getString("status")),
                        rs.getString("decline_reason"),
                        rs.getDouble("fraud_score"),
                        rs.getInt("suspicious") == 1,
                        rs.getLong("created_at_epoch_ms"),
                        rs.getInt("merchant_attempted") == 1
                ),
                windowStartEpochMs
        );
    }

    @Override
    public Payment findArchivedPayment(String paymentId) {
        return queryForObjectOrNull(
                """
                SELECT payment_id,
                       account_id,
                       merchant_id,
                       amount_minor,
                       currency,
                       status,
                       created_at_epoch_ms,
                       updated_at_epoch_ms,
                       decline_reason,
                       fraud_score,
                       suspicious,
                       captured_at_epoch_ms,
                       refunded_at_epoch_ms
                FROM PAYMENT_ARCHIVE
                WHERE payment_id = ?
                """,
                ARCHIVED_PAYMENT_ROW_MAPPER,
                paymentId
        );
    }

    @Transactional
    @Override
    public boolean archiveCompletedPayment(Payment payment, MerchantPaymentAttempt attempt, List<LedgerEntry> ledgerEntries) {
        long existing = queryForLong(
                "SELECT COUNT(*) FROM PAYMENT_ARCHIVE WHERE payment_id = ?",
                payment.getPaymentId()
        );
        if (existing > 0) {
            return false;
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE ACCOUNTS
                    SET available_balance_minor = available_balance_minor - ?
                    WHERE account_id = ?
                    """,
                    payment.getAmountMinor(),
                    payment.getAccountId()
            );
            if (updated == 0) {
                throw new IllegalStateException("external DB account missing for completed payment " + payment.getPaymentId());
            }
        }

        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_ARCHIVE (
                    payment_id,
                    account_id,
                    merchant_id,
                    amount_minor,
                    currency,
                    status,
                    created_at_epoch_ms,
                    updated_at_epoch_ms,
                    decline_reason,
                    fraud_score,
                    suspicious,
                    captured_at_epoch_ms,
                    refunded_at_epoch_ms,
                    merchant_attempted,
                    merchant_status,
                    merchant_requested_at_epoch_ms,
                    merchant_deadline_epoch_ms,
                    merchant_responded_at_epoch_ms,
                    merchant_reference,
                    merchant_message,
                    archived_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                payment.getPaymentId(),
                payment.getAccountId(),
                payment.getMerchantId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getCreatedAtEpochMs(),
                payment.getUpdatedAtEpochMs(),
                payment.getDeclineReason(),
                payment.getFraudScore(),
                payment.isSuspicious() ? 1 : 0,
                payment.getCapturedAtEpochMs(),
                payment.getRefundedAtEpochMs(),
                attempt == null ? 0 : 1,
                attempt == null ? null : attempt.getStatus().name(),
                attempt == null ? 0L : attempt.getRequestedAtEpochMs(),
                attempt == null ? 0L : attempt.getDeadlineEpochMs(),
                attempt == null ? 0L : attempt.getRespondedAtEpochMs(),
                attempt == null ? null : attempt.getMerchantReference(),
                attempt == null ? null : attempt.getMessage(),
                payment.getUpdatedAtEpochMs()
        );

        if (!ledgerEntries.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO LEDGER_ENTRY_ARCHIVE (
                        entry_id,
                        payment_id,
                        account_id,
                        merchant_id,
                        direction,
                        amount_minor,
                        currency,
                        entry_type,
                        created_at_epoch_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int index) throws SQLException {
                            LedgerEntry entry = ledgerEntries.get(index);
                            ps.setString(1, entry.getEntryId());
                            ps.setString(2, entry.getPaymentId());
                            ps.setString(3, entry.getAccountId());
                            ps.setString(4, entry.getMerchantId());
                            ps.setString(5, entry.getDirection().name());
                            ps.setLong(6, entry.getAmountMinor());
                            ps.setString(7, entry.getCurrency());
                            ps.setString(8, entry.getEntryType());
                            ps.setLong(9, entry.getCreatedAtEpochMs());
                        }

                        @Override
                        public int getBatchSize() {
                            return ledgerEntries.size();
                        }
                    }
            );
        }

        return true;
    }

    @Override
    public boolean supportsSemanticInvestigation() {
        return databaseType == ExternalDatabaseType.MARIADB;
    }

    @Transactional
    @Override
    public void upsertPaymentSemanticIndex(SemanticPaymentIndexEntry entry) {
        if (!supportsSemanticInvestigation()) {
            return;
        }
        ensureSemanticIndexTable();

        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_SEMANTIC_INDEX (
                    payment_id,
                    summary,
                    status,
                    merchant_id,
                    amount_minor,
                    currency,
                    fraud_score,
                    suspicious,
                    decline_reason,
                    created_at_epoch_ms,
                    source,
                    updated_at_epoch_ms,
                    embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, VEC_FromText(?))
                ON DUPLICATE KEY UPDATE
                    summary = VALUES(summary),
                    status = VALUES(status),
                    merchant_id = VALUES(merchant_id),
                    amount_minor = VALUES(amount_minor),
                    currency = VALUES(currency),
                    fraud_score = VALUES(fraud_score),
                    suspicious = VALUES(suspicious),
                    decline_reason = VALUES(decline_reason),
                    created_at_epoch_ms = VALUES(created_at_epoch_ms),
                    source = VALUES(source),
                    updated_at_epoch_ms = VALUES(updated_at_epoch_ms),
                    embedding = VALUES(embedding)
                """,
                entry.paymentId(),
                entry.summary(),
                entry.status().name(),
                entry.merchantId(),
                entry.amountMinor(),
                entry.currency(),
                entry.fraudScore(),
                entry.suspicious() ? 1 : 0,
                entry.declineReason(),
                entry.createdAtEpochMs(),
                entry.source(),
                System.currentTimeMillis(),
                entry.embeddingJson()
        );
    }

    @Override
    public List<SemanticInvestigationResult> searchSimilarPayments(String embeddingJson, int limit) {
        if (!supportsSemanticInvestigation()) {
            return List.of();
        }
        ensureSemanticIndexTable();

        return jdbcTemplate.query(
                """
                SELECT payment_id,
                       summary,
                       status,
                       merchant_id,
                       amount_minor,
                       currency,
                       fraud_score,
                       suspicious,
                       decline_reason,
                       created_at_epoch_ms,
                       source,
                       VEC_DISTANCE_COSINE(embedding, VEC_FromText(?)) AS distance
                FROM PAYMENT_SEMANTIC_INDEX
                ORDER BY distance
                LIMIT ?
                """,
                (rs, ignored) -> {
                    double distance = rs.getDouble("distance");
                    double relevancePercent = Math.max(0.0, Math.min(100.0, (1.0 - distance) * 100.0));
                    return new SemanticInvestigationResult(
                            rs.getString("payment_id"),
                            rs.getString("summary"),
                            rs.getString("status"),
                            rs.getString("merchant_id"),
                            rs.getLong("amount_minor"),
                            rs.getString("currency"),
                            rs.getDouble("fraud_score"),
                            rs.getInt("suspicious") == 1,
                            rs.getString("decline_reason"),
                            rs.getLong("created_at_epoch_ms"),
                            rs.getString("source"),
                            distance,
                            relevancePercent
                    );
                },
                embeddingJson,
                limit
        );
    }

    @Override
    public long semanticPaymentIndexCount() {
        if (!supportsSemanticInvestigation()) {
            return 0;
        }
        ensureSemanticIndexTable();

        return queryForLong("SELECT COUNT(*) FROM PAYMENT_SEMANTIC_INDEX");
    }

    @Override
    public void enableReferenceTableCdc() {
        if (databaseType != ExternalDatabaseType.ORACLE) {
            return;
        }

        executeOptionalSql("ALTER TABLE ACCOUNTS ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
        executeOptionalSql("ALTER TABLE MERCHANTS ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
    }

    private String ddl(String sql) {
        String varcharType = databaseType == ExternalDatabaseType.ORACLE ? "VARCHAR2" : "VARCHAR";
        String longType = databaseType == ExternalDatabaseType.ORACLE ? "NUMBER(19)" : "BIGINT";
        String booleanType = databaseType == ExternalDatabaseType.ORACLE ? "NUMBER(1)" : "TINYINT";
        String decimalType = databaseType == ExternalDatabaseType.ORACLE ? "NUMBER(10,4)" : "DECIMAL(10,4)";

        return sql
                .replace("$VARCHAR", varcharType)
                .replace("$LONG", longType)
                .replace("$BOOLEAN", booleanType)
                .replace("$DECIMAL", decimalType);
    }

    private void ensureSemanticIndexTable() {
        if (!supportsSemanticInvestigation() || semanticIndexSchemaChecked) {
            return;
        }

        synchronized (this) {
            if (semanticIndexSchemaChecked) {
                return;
            }
            executeDdl(semanticIndexDdl());
            if (!semanticIndexTableExists()) {
                log.warn("MariaDB semantic index table was reported as existing but is not visible; recreating it.");
                executeOptionalSql("DROP TABLE IF EXISTS PAYMENT_SEMANTIC_INDEX");
                executeDdl(semanticIndexDdl());
            }
            if (!semanticIndexTableExists()) {
                throw new IllegalStateException("MariaDB semantic index table is not available after schema initialization.");
            }
            semanticIndexSchemaChecked = true;
        }
    }

    private boolean semanticIndexTableExists() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'PAYMENT_SEMANTIC_INDEX'
                """,
                Long.class
        );
        return count != null && count > 0;
    }

    private String semanticIndexDdl() {
        return """
                CREATE TABLE PAYMENT_SEMANTIC_INDEX (
                    PAYMENT_ID VARCHAR(64) CHARACTER SET ascii PRIMARY KEY,
                    SUMMARY TEXT NOT NULL,
                    STATUS VARCHAR(32) NOT NULL,
                    MERCHANT_ID VARCHAR(64) NOT NULL,
                    AMOUNT_MINOR BIGINT NOT NULL,
                    CURRENCY VARCHAR(8) NOT NULL,
                    FRAUD_SCORE DECIMAL(10,4) NOT NULL,
                    SUSPICIOUS TINYINT NOT NULL,
                    DECLINE_REASON VARCHAR(128),
                    CREATED_AT_EPOCH_MS BIGINT NOT NULL,
                    SOURCE VARCHAR(32) NOT NULL,
                    UPDATED_AT_EPOCH_MS BIGINT NOT NULL,
                    EMBEDDING VECTOR(64) NOT NULL,
                    VECTOR INDEX (EMBEDDING) M=8 DISTANCE=cosine
                )
                """;
    }

    private String accountUpsertSql() {
        if (databaseType == ExternalDatabaseType.MARIADB) {
            return """
                    INSERT INTO ACCOUNTS (
                        ACCOUNT_ID,
                        CUSTOMER_NAME,
                        AVAILABLE_BALANCE_MINOR,
                        CURRENCY,
                        STATUS,
                        RISK_TIER
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        CUSTOMER_NAME = VALUES(CUSTOMER_NAME),
                        AVAILABLE_BALANCE_MINOR = VALUES(AVAILABLE_BALANCE_MINOR),
                        CURRENCY = VALUES(CURRENCY),
                        STATUS = VALUES(STATUS),
                        RISK_TIER = VALUES(RISK_TIER)
                    """;
        }

        return """
                MERGE INTO ACCOUNTS target
                USING (
                    SELECT ? AS ACCOUNT_ID,
                           ? AS CUSTOMER_NAME,
                           ? AS AVAILABLE_BALANCE_MINOR,
                           ? AS CURRENCY,
                           ? AS STATUS,
                           ? AS RISK_TIER
                    FROM dual
                ) source
                ON (target.ACCOUNT_ID = source.ACCOUNT_ID)
                WHEN MATCHED THEN UPDATE SET
                    CUSTOMER_NAME = source.CUSTOMER_NAME,
                    AVAILABLE_BALANCE_MINOR = source.AVAILABLE_BALANCE_MINOR,
                    CURRENCY = source.CURRENCY,
                    STATUS = source.STATUS,
                    RISK_TIER = source.RISK_TIER
                WHEN NOT MATCHED THEN INSERT (
                    ACCOUNT_ID,
                    CUSTOMER_NAME,
                    AVAILABLE_BALANCE_MINOR,
                    CURRENCY,
                    STATUS,
                    RISK_TIER
                ) VALUES (
                    source.ACCOUNT_ID,
                    source.CUSTOMER_NAME,
                    source.AVAILABLE_BALANCE_MINOR,
                    source.CURRENCY,
                    source.STATUS,
                    source.RISK_TIER
                )
                """;
    }

    private String merchantUpsertSql() {
        if (databaseType == ExternalDatabaseType.MARIADB) {
            return """
                    INSERT INTO MERCHANTS (
                        MERCHANT_ID,
                        NAME,
                        CATEGORY,
                        COUNTRY,
                        ACTIVE,
                        MAX_AMOUNT_MINOR,
                        DAILY_LIMIT_MINOR,
                        SERVICE_URL
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        NAME = VALUES(NAME),
                        CATEGORY = VALUES(CATEGORY),
                        COUNTRY = VALUES(COUNTRY),
                        ACTIVE = VALUES(ACTIVE),
                        MAX_AMOUNT_MINOR = VALUES(MAX_AMOUNT_MINOR),
                        DAILY_LIMIT_MINOR = VALUES(DAILY_LIMIT_MINOR),
                        SERVICE_URL = VALUES(SERVICE_URL)
                    """;
        }

        return """
                MERGE INTO MERCHANTS target
                USING (
                    SELECT ? AS MERCHANT_ID,
                           ? AS NAME,
                           ? AS CATEGORY,
                           ? AS COUNTRY,
                           ? AS ACTIVE,
                           ? AS MAX_AMOUNT_MINOR,
                           ? AS DAILY_LIMIT_MINOR,
                           ? AS SERVICE_URL
                    FROM dual
                ) source
                ON (target.MERCHANT_ID = source.MERCHANT_ID)
                WHEN MATCHED THEN UPDATE SET
                    NAME = source.NAME,
                    CATEGORY = source.CATEGORY,
                    COUNTRY = source.COUNTRY,
                    ACTIVE = source.ACTIVE,
                    MAX_AMOUNT_MINOR = source.MAX_AMOUNT_MINOR,
                    DAILY_LIMIT_MINOR = source.DAILY_LIMIT_MINOR,
                    SERVICE_URL = source.SERVICE_URL
                WHEN NOT MATCHED THEN INSERT (
                    MERCHANT_ID,
                    NAME,
                    CATEGORY,
                    COUNTRY,
                    ACTIVE,
                    MAX_AMOUNT_MINOR,
                    DAILY_LIMIT_MINOR,
                    SERVICE_URL
                ) VALUES (
                    source.MERCHANT_ID,
                    source.NAME,
                    source.CATEGORY,
                    source.COUNTRY,
                    source.ACTIVE,
                    source.MAX_AMOUNT_MINOR,
                    source.DAILY_LIMIT_MINOR,
                    source.SERVICE_URL
                )
                """;
    }

    private void executeDdl(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException e) {
            if (!isAlreadyExists(e)) {
                throw e;
            }
            log.debug("Skipping existing external DB object for DDL: {}", sql);
        }
    }

    private boolean isAlreadyExists(DataAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 955
                    || sqlException.getErrorCode() == 1050
                    || "42S01".equals(sqlException.getSQLState()))) {
                return true;
            }

            if (cause.getMessage() != null && (
                    cause.getMessage().contains("ORA-00955")
                            || cause.getMessage().contains("ORA-32588")
                            || cause.getMessage().contains("ORA-32589")
                            || cause.getMessage().contains("already exists")
            )) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void executeOptionalSql(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException e) {
            if (!isAlreadyExists(e)) {
                throw e;
            }
            log.debug("Skipping existing external DB CDC setup state for SQL: {}", sql);
        }
    }

    private long queryForLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private <T> T queryForObjectOrNull(String sql, RowMapper<T> rowMapper, Object... args) {
        List<T> results = jdbcTemplate.query(sql, rowMapper, args);
        return results.isEmpty() ? null : results.get(0);
    }
}
