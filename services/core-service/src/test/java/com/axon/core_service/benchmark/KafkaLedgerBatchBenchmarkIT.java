package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.commandprocessing.FcfsLedgerPersistenceService;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(showSql = false, properties = {
        "logging.level.org.hibernate.SQL=OFF", "logging.level.com.axon=INFO"})
@ActiveProfiles("test")
@ContextConfiguration(classes = com.axon.core_service.CoreServiceApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FcfsLedgerPersistenceService.class)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "KAFKA_LEDGER_BENCHMARK", matches = "true")
class KafkaLedgerBatchBenchmarkIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("ledger_benchmark");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired FcfsLedgerPersistenceService ledger;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignActivityRepository activities;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void comparesSingleAndTwentyRecordTransactions() throws Exception {
        Path output = Path.of(System.getenv().getOrDefault("BENCHMARK_ARTIFACT_DIR",
                "../../artifacts/benchmark/kafka-ledger-" + System.currentTimeMillis()));
        Files.createDirectories(output);
        List<String> rows = new ArrayList<>(List.of(
                "run,batch_limit,records,elapsed_ms,records_per_second,transactions,max_committed_lag,final_lag"));
        try {
            measure("warmup-single", 1, 200, output);
            measure("warmup-batch", 20, 200, output);
            for (int iteration = 1; iteration <= 3; iteration++) {
                for (int batch : iteration % 2 == 1 ? new int[] {1, 20} : new int[] {20, 1}) {
                    rows.add(measure("measured-" + iteration, batch, 800, output));
                    Files.write(output.resolve("results.csv"), rows);
                }
            }
        } finally {
            Files.writeString(output.resolve("scope.md"), """
                    # Local Kafka ledger batch comparison
                    - Same current FcfsLedgerPersistenceService, real Kafka and isolated MySQL 8.0.46.
                    - One partition, one consumer, max.poll.records=1 versus 20, synchronous offset commit per poll.
                    - Actual CampaignActivityEntry and Purchase persistence, not bulk INSERT.
                    - 200-record warm-up per condition, 800 records per measured run, three alternating pairs.
                    - Timer includes polling, JSON parsing, DB transaction commit and Kafka offset commit.
                    - Producer preloads all records before timing. Initial/max committed lag is therefore 800 in BOTH conditions.
                    - elapsed_ms is backlog drain time, not individual transaction latency or live traffic latency.
                    - Excludes Entry, HTTP, UserSummary projection and purchase behavior-event publication.
                    - Controlled current-code comparison, NOT a reconstruction of historical single-message code.
                    """);
        }
    }

    private String measure(String name, int batch, int count, Path output) throws Exception {
        String bootstrap = KAFKA.getBootstrapServers();
        String unique = "ledger-bench-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(unique, 0);
        Campaign parent = campaigns.saveAndFlush(Campaign.builder().name(unique).build());
        LocalDateTime now = LocalDateTime.now();
        CampaignActivity activity = activities.saveAndFlush(CampaignActivity.builder().campaign(parent)
                .name(unique).limitCount(count).status(CampaignActivityStatus.ACTIVE)
                .startDate(now.minusDays(1)).endDate(now.plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE).price(BigDecimal.valueOf(1000)).quantity(1).build());
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(unique, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
            try {
                try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                        "bootstrap.servers", bootstrap, "acks", "all",
                        "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
                    for (int i = 1; i <= count; i++) {
                        var message = CampaignActivityKafkaProducerDto.builder()
                                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE).campaignActivityId(activity.getId())
                                .userId((long) i).productId(1L).quantity(1L)
                                .timestamp(System.currentTimeMillis()).build();
                        producer.send(new ProducerRecord<>(unique, 0, "" + i, json.writeValueAsString(message)));
                    }
                    producer.flush();
                }
                Properties properties = new Properties();
                properties.putAll(Map.of("bootstrap.servers", bootstrap, "group.id", unique,
                        "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                        "enable.auto.commit", "false", ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "" + batch));
                List<String> lag = new ArrayList<>(List.of("elapsed_ms,committed_lag", "0," + count));
                int processed = 0;
                int transactions = 0;
                double elapsed;
                try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
                    consumer.assign(List.of(partition));
                    consumer.seek(partition, 0);
                    consumer.endOffsets(List.of(partition));
                    long started = System.nanoTime();
                    while (processed < count) {
                        if (System.nanoTime() - started > Duration.ofMinutes(2).toNanos()) {
                            throw new IllegalStateException("Consumer did not drain within two minutes");
                        }
                        var records = consumer.poll(Duration.ofMillis(100));
                        List<CampaignActivityKafkaProducerDto> messages = new ArrayList<>();
                        long nextOffset = 0;
                        for (var record : records) {
                            messages.add(json.readValue(record.value(), CampaignActivityKafkaProducerDto.class));
                            nextOffset = record.offset() + 1;
                        }
                        if (messages.isEmpty()) continue;
                        ledger.persistBatch(messages);
                        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(nextOffset)));
                        processed += messages.size();
                        transactions++;
                        lag.add(((System.nanoTime() - started) / 1_000_000.0) + "," + (count - nextOffset));
                    }
                    elapsed = (System.nanoTime() - started) / 1_000_000.0;
                    assertThat(consumer.committed(java.util.Set.of(partition)).get(partition).offset()).isEqualTo(count);
                }
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchases WHERE campaign_activity_id=?",
                        Long.class, activity.getId())).isEqualTo((long) count);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id=?",
                        Long.class, activity.getId())).isEqualTo((long) count);
                Files.write(output.resolve(name + "-batch-" + batch + "-lag.csv"), lag);
                return String.format(java.util.Locale.ROOT, "%s,%d,%d,%.3f,%.3f,%d,%d,0",
                        name, batch, count, elapsed, count * 1000.0 / elapsed, transactions, count);
            } finally {
                admin.deleteTopics(List.of(unique)).all().get(30, TimeUnit.SECONDS);
            }
        }
    }
}
