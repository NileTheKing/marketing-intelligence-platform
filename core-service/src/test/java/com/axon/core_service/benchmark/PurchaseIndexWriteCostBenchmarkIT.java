package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Isolates the write cost of the cohort-history secondary index. It intentionally excludes
 * Kafka, Entry, UserSummary, and HTTP so those paths cannot hide the index cost.
 */
@Tag("benchmark")
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "purchase.index.write.benchmark", matches = "true")
class PurchaseIndexWriteCostBenchmarkIT {

    private static final String USER_HISTORY_INDEX = "idx_purchase_user_history";

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("axon_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl() + "?rewriteBatchedStatements=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void comparesPurchaseWritesWithAndWithoutUserHistoryIndex() throws Exception {
        WriteConfig config = WriteConfig.fromSystemProperties();
        Files.createDirectories(config.artifactDir());
        seedBackground(config);

        List<WriteRun> withoutIndex = new ArrayList<>();
        List<WriteRun> withIndex = new ArrayList<>();
        try {
            for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                // Alternate order so a consistently warmer first or second condition does not decide the result.
                if (iteration % 2 == 1) {
                    withoutIndex.add(measure("without-index-" + iteration, false, iteration, config));
                    withIndex.add(measure("with-index-" + iteration, true, iteration, config));
                } else {
                    withIndex.add(measure("with-index-" + iteration, true, iteration, config));
                    withoutIndex.add(measure("without-index-" + iteration, false, iteration, config));
                }
            }
        } finally {
            enableUserHistoryIndex();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class)).isEqualTo(config.backgroundRows());
        writeReport(config, withoutIndex, withIndex);
    }

    private void seedBackground(WriteConfig config) {
        jdbc.update("DELETE FROM purchases");
        String insert = """
                INSERT INTO purchases
                    (user_id, product_id, campaign_activity_id, purchase_type, price, quantity, purchase_at, status)
                VALUES (?, ?, NULL, 'SHOP', ?, 1, NOW(), 'CONFIRMED')
                """;
        List<Object[]> rows = new ArrayList<>(1_000);
        for (int row = 0; row < config.backgroundRows(); row++) {
            rows.add(new Object[] {(long) (row % config.backgroundUsers()) + 1, 1L, BigDecimal.valueOf(10_000L + row % 10)});
            if (rows.size() == 1_000) {
                jdbc.batchUpdate(insert, rows);
                rows.clear();
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(insert, rows);
        }
    }

    private WriteRun measure(String name, boolean indexEnabled, int iteration, WriteConfig config) {
        if (indexEnabled) {
            enableUserHistoryIndex();
        } else {
            disableUserHistoryIndex();
        }

        long firstUserId = 10_000_000L + (long) iteration * 10_000 + (indexEnabled ? 5_000 : 0);
        List<Long> batchDurations = new ArrayList<>();
        long started = System.nanoTime();
        for (int offset = 0; offset < config.writeRows(); offset += config.transactionBatchSize()) {
            int batchStart = offset;
            long batchStarted = System.nanoTime();
            transactionTemplate.executeWithoutResult(status -> {
                for (int index = 0; index < config.transactionBatchSize(); index++) {
                    entityManager.persist(new Purchase(
                            firstUserId + batchStart + index,
                            1L,
                            null,
                            PurchaseType.SHOP,
                            BigDecimal.valueOf(10_000L + (batchStart + index) % 10),
                            1,
                            Instant.now()));
                }
                // Purchase uses IDENTITY, so Hibernate issues per-entity inserts before this flush.
                entityManager.flush();
                entityManager.clear();
            });
            batchDurations.add((System.nanoTime() - batchStarted) / 1_000_000);
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        jdbc.update("DELETE FROM purchases WHERE user_id >= ? AND user_id < ?", firstUserId, firstUserId + config.writeRows());
        return new WriteRun(name, elapsedMillis, percentile(batchDurations, 0.95));
    }

    private void enableUserHistoryIndex() {
        if (!hasUserHistoryIndex()) {
            jdbc.execute("CREATE INDEX idx_purchase_user_history ON purchases (user_id, purchase_at)");
        }
    }

    private void disableUserHistoryIndex() {
        if (hasUserHistoryIndex()) {
            jdbc.execute("DROP INDEX idx_purchase_user_history ON purchases");
        }
    }

    private boolean hasUserHistoryIndex() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'purchases'
                  AND index_name = ?
                """, Integer.class, USER_HISTORY_INDEX);
        return count != null && count > 0;
    }

    private void writeReport(WriteConfig config, List<WriteRun> withoutIndex, List<WriteRun> withIndex) throws Exception {
        String report = """
                # Purchase Index Write-Cost Benchmark

                This isolates the additional MySQL/JPA write cost of `idx_purchase_user_history`.
                It excludes Kafka, Entry, UserSummary, and HTTP; it is not an end-to-end throughput benchmark.

                ## Dataset and write shape

                - Background Purchase rows: %d
                - Measured Purchase inserts per run: %d
                - Transaction batch size: %d
                - Purchase ID generation: IDENTITY (per-entity inserts, not JDBC bulk insert)
                - Measured iterations per condition: %d

                ## Timing

                | Condition | Total insert runs (ms) | Median total (ms) | Median throughput (rows/s) | Batch p95 runs (ms) | Median batch p95 (ms) |
                | --- | --- | --- | --- | --- | --- |
                | Without `(user_id, purchase_at)` index | %s | %d | %.1f | %s | %d |
                | With `(user_id, purchase_at)` index | %s | %d | %.1f | %s | %d |

                The table is restored with the index at test completion. Index DDL and cleanup deletes are excluded from timing.
                """.formatted(
                config.backgroundRows(), config.writeRows(), config.transactionBatchSize(), config.iterations(),
                durations(withoutIndex, WriteRun::elapsedMillis), median(withoutIndex, WriteRun::elapsedMillis), throughput(config.writeRows(), median(withoutIndex, WriteRun::elapsedMillis)), durations(withoutIndex, WriteRun::batchP95Millis), median(withoutIndex, WriteRun::batchP95Millis),
                durations(withIndex, WriteRun::elapsedMillis), median(withIndex, WriteRun::elapsedMillis), throughput(config.writeRows(), median(withIndex, WriteRun::elapsedMillis)), durations(withIndex, WriteRun::batchP95Millis), median(withIndex, WriteRun::batchP95Millis));
        Files.writeString(config.artifactDir().resolve("summary.md"), report);
    }

    private long percentile(List<Long> values, double percentile) {
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index);
    }

    private String durations(List<WriteRun> runs, java.util.function.ToLongFunction<WriteRun> extractor) {
        return runs.stream().map(run -> Long.toString(extractor.applyAsLong(run))).collect(java.util.stream.Collectors.joining(", "));
    }

    private long median(List<WriteRun> runs, java.util.function.ToLongFunction<WriteRun> extractor) {
        return runs.stream().mapToLong(extractor).boxed().sorted(Comparator.naturalOrder()).skip(runs.size() / 2).findFirst().orElseThrow();
    }

    private double throughput(int rows, long elapsedMillis) {
        return elapsedMillis == 0 ? Double.POSITIVE_INFINITY : rows * 1_000.0 / elapsedMillis;
    }

    private record WriteRun(String name, long elapsedMillis, long batchP95Millis) {}

    private record WriteConfig(int backgroundRows, int backgroundUsers, int writeRows, int transactionBatchSize, int iterations, Path artifactDir) {
        private static WriteConfig fromSystemProperties() {
            int writeRows = integer("purchase.index.write.rows", 800);
            int transactionBatchSize = integer("purchase.index.write.transaction-batch-size", 20);
            if (writeRows % transactionBatchSize != 0) {
                throw new IllegalArgumentException("purchase.index.write.rows must be divisible by transaction batch size");
            }
            return new WriteConfig(
                    integer("purchase.index.write.background-rows", 1_000_000),
                    integer("purchase.index.write.background-users", 100_000),
                    writeRows,
                    transactionBatchSize,
                    integer("purchase.index.write.iterations", 5),
                    Path.of(System.getProperty("purchase.index.write.artifact-dir", "artifacts/benchmark/purchase-index-write-cost")));
        }

        private static int integer(String name, int defaultValue) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        }
    }
}
