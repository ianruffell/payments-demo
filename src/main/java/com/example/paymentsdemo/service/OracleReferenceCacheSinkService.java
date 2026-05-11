package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.AccountStatus;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.RiskTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ignite.Ignite;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("oracle-cache-sink")
public class OracleReferenceCacheSinkService {

    private static final Logger log = LoggerFactory.getLogger(OracleReferenceCacheSinkService.class);

    private final Ignite ignite;
    private final ObjectMapper objectMapper;
    private final String bootstrapServers;
    private final String groupId;
    private final String topicPrefix;
    private final String schemaName;
    private final String accountsTopicName;
    private final String merchantsTopicName;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public OracleReferenceCacheSinkService(
            Ignite ignite,
            ObjectMapper objectMapper,
            @Value("${demo.oracle.cdc.kafka-bootstrap-servers}") String bootstrapServers,
            @Value("${demo.oracle.cdc.sink-group-id}") String groupId,
            @Value("${demo.oracle.cdc.topic-prefix}") String topicPrefix,
            @Value("${demo.oracle.cdc.schema-name}") String schemaName,
            @Value("${demo.oracle.cdc.accounts-topic:ACCOUNTS}") String accountsTopicName,
            @Value("${demo.oracle.cdc.merchants-topic:MERCHANTS}") String merchantsTopicName
    ) {
        this.ignite = ignite;
        this.objectMapper = objectMapper;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.topicPrefix = topicPrefix;
        this.schemaName = schemaName;
        this.accountsTopicName = accountsTopicName;
        this.merchantsTopicName = merchantsTopicName;
    }

    @PostConstruct
    public void start() {
        executor.submit(this::consumeLoop);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        executor.shutdownNow();
    }

    private void consumeLoop() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        String accountTopic = topicPrefix + "." + schemaName + "." + accountsTopicName;
        String merchantTopic = topicPrefix + "." + schemaName + "." + merchantsTopicName;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(accountTopic, merchantTopic));
            log.info("Oracle CDC cache sink subscribed to {}", List.of(accountTopic, merchantTopic));

            while (running.get()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    processRecord(record);
                }
            }
        } catch (RuntimeException e) {
            if (running.get()) {
                throw e;
            }
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            String op = root.path("op").asText();
            JsonNode after = root.get("after");
            JsonNode before = root.get("before");

            if (record.topic().endsWith("." + accountsTopicName)) {
                applyAccountChange(op, after, before);
                return;
            }

            if (record.topic().endsWith("." + merchantsTopicName)) {
                applyMerchantChange(op, after, before);
            }
        } catch (Exception e) {
            log.warn("Failed to apply Oracle CDC record from topic {}", record.topic(), e);
        }
    }

    private void applyAccountChange(String op, JsonNode after, JsonNode before) {
        if ("d".equals(op) && before != null && !before.isNull()) {
            ignite.cache(CacheNames.ACCOUNTS).remove(before.path("ACCOUNT_ID").asText());
            return;
        }

        if (after == null || after.isNull()) {
            return;
        }

        Account account = new Account(
                after.path("ACCOUNT_ID").asText(),
                after.path("CUSTOMER_NAME").asText(),
                after.path("AVAILABLE_BALANCE_MINOR").asLong(),
                after.path("CURRENCY").asText(),
                AccountStatus.valueOf(after.path("STATUS").asText()),
                RiskTier.valueOf(after.path("RISK_TIER").asText())
        );
        ignite.cache(CacheNames.ACCOUNTS).put(account.getAccountId(), account);
    }

    private void applyMerchantChange(String op, JsonNode after, JsonNode before) {
        if ("d".equals(op) && before != null && !before.isNull()) {
            ignite.cache(CacheNames.MERCHANTS).remove(before.path("MERCHANT_ID").asText());
            return;
        }

        if (after == null || after.isNull()) {
            return;
        }

        Merchant merchant = new Merchant(
                after.path("MERCHANT_ID").asText(),
                after.path("NAME").asText(),
                after.path("CATEGORY").asText(),
                after.path("COUNTRY").asText(),
                after.path("ACTIVE").asInt() == 1,
                after.path("MAX_AMOUNT_MINOR").asLong(),
                after.path("DAILY_LIMIT_MINOR").asLong(),
                after.path("SERVICE_URL").asText()
        );
        ignite.cache(CacheNames.MERCHANTS).put(merchant.getMerchantId(), merchant);
    }
}
