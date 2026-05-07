package com.example.paymentsdemo.cdc;

import com.example.paymentsdemo.config.CacheConfigurations;
import com.example.paymentsdemo.domain.Account;
import com.example.paymentsdemo.domain.LedgerEntry;
import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.domain.Payment;
import com.example.paymentsdemo.service.CacheNames;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
@Profile("cdc-sink")
public class KafkaToGridGainSinkApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KafkaToGridGainSinkApplication.class);

    private static final String ACCOUNTS_TOPIC = CacheNames.ACCOUNTS;
    private static final String MERCHANTS_TOPIC = CacheNames.MERCHANTS;
    private static final String PAYMENTS_TOPIC = CacheNames.PAYMENTS;
    private static final String LEDGER_ENTRIES_TOPIC = CacheNames.LEDGER_ENTRIES;

    private final ObjectMapper objectMapper;
    private final String kafkaBootstrapServers;
    private final String kafkaGroupId;
    private final List<String> topics;
    private final String instanceName;
    private final List<String> discoveryAddresses;

    public KafkaToGridGainSinkApplication(
            @Value("${sink.kafka.bootstrap-servers:localhost:9092}") String kafkaBootstrapServers,
            @Value("${sink.kafka.group-id:payments-demo-gridgain-sink}") String kafkaGroupId,
            @Value("${sink.kafka.topics:accounts,merchants,payments,ledger_entries}") List<String> topics,
            @Value("${sink.gridgain.instance-name:payments-demo-cdc-sink}") String instanceName,
            @Value("${sink.gridgain.discovery-addresses:127.0.0.1:47500,127.0.0.1:47501,127.0.0.1:47502}") String discoveryAddresses
    ) {
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.kafkaGroupId = kafkaGroupId;
        this.topics = topics;
        this.instanceName = instanceName;
        this.discoveryAddresses = Arrays.stream(discoveryAddresses.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(KafkaToGridGainSinkApplication.class)
                .profiles("cdc-sink")
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);

        try (Ignite ignite = startIgniteClient();
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
            consumer.subscribe(topics);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                consumer.wakeup();
            }));

            log.info("Kafka-to-GridGain sink started. kafka={} topics={} discovery={}",
                    kafkaBootstrapServers,
                    topics,
                    discoveryAddresses);

            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    applyRecord(ignite, record);
                }

                if (!records.isEmpty()) {
                    consumer.commitSync();
                    log.info("Committed {} record(s)", records.count());
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        }
    }

    private Ignite startIgniteClient() {
        IgniteConfiguration cfg = new IgniteConfiguration();
        cfg.setIgniteInstanceName(instanceName);
        cfg.setClientMode(true);
        cfg.setDiscoverySpi(discoverySpi());

        Ignite ignite = Ignition.start(cfg);
        for (var cacheConfiguration : CacheConfigurations.all()) {
            ignite.getOrCreateCache(cacheConfiguration);
        }
        return ignite;
    }

    private TcpDiscoverySpi discoverySpi() {
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(discoveryAddresses);

        TcpDiscoverySpi discoverySpi = new TcpDiscoverySpi();
        discoverySpi.setIpFinder(ipFinder);
        return discoverySpi;
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaGroupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return properties;
    }

    private void applyRecord(Ignite ignite, ConsumerRecord<String, String> record) throws Exception {
        JsonNode key = readJson(record.key());
        JsonNode value = readJson(record.value());

        switch (record.topic()) {
            case ACCOUNTS_TOPIC -> applyUpsertOrDelete(
                    ignite.cache(CacheNames.ACCOUNTS),
                    key,
                    value,
                    "accountId",
                    Account.class
            );
            case MERCHANTS_TOPIC -> applyUpsertOrDelete(
                    ignite.cache(CacheNames.MERCHANTS),
                    key,
                    value,
                    "merchantId",
                    Merchant.class
            );
            case PAYMENTS_TOPIC -> applyUpsertOrDelete(
                    ignite.cache(CacheNames.PAYMENTS),
                    key,
                    value,
                    "paymentId",
                    Payment.class
            );
            case LEDGER_ENTRIES_TOPIC -> applyUpsertOrDelete(
                    ignite.cache(CacheNames.LEDGER_ENTRIES),
                    key,
                    value,
                    "entryId",
                    LedgerEntry.class
            );
            default -> log.warn("Skipping unsupported topic {}", record.topic());
        }
    }

    private <T> void applyUpsertOrDelete(
            org.apache.ignite.IgniteCache<String, T> cache,
            JsonNode key,
            JsonNode value,
            String idField,
            Class<T> type
    ) throws Exception {
        String cacheKey = requiredText(key, idField);

        if (value == null || isDeleteEvent(value)) {
            cache.remove(cacheKey);
            log.info("Deleted {} from cache {}", cacheKey, cache.getName());
            return;
        }

        T mappedValue = objectMapper.treeToValue(value, type);
        cache.put(cacheKey, mappedValue);
        log.info("Upserted {} into cache {}", cacheKey, cache.getName());
    }

    private JsonNode readJson(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return null;
        }
        return objectMapper.readTree(value);
    }

    private boolean isDeleteEvent(JsonNode value) {
        JsonNode deletedNode = value.get("__deleted");
        return deletedNode != null && "true".equalsIgnoreCase(deletedNode.asText());
    }

    private String requiredText(JsonNode node, String fieldName) {
        if (node == null || node.get(fieldName) == null || node.get(fieldName).asText().isBlank()) {
            throw new IllegalArgumentException("Missing key field " + fieldName);
        }
        return node.get(fieldName).asText();
    }
}
