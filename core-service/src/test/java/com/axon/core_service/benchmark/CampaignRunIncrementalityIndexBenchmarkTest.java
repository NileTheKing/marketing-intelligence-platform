package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Opt-in query-shape benchmark for the campaign incrementality read model.
 * It excludes HTTP, Kafka, and application startup.
 */
@Tag("benchmark")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campaign.incrementality.benchmark", matches = "true")
class CampaignRunIncrementalityIndexBenchmarkTest {

    private static final long RUN_ID = 1L;
    private static final int TARGET_USERS = 100_000;
    private static final int HOLDOUT_USERS = 10_000;
    private static final int BATCH_SIZE = 5_000;
    private static final LocalDateTime DISPATCHED_AT = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime OBSERVED_UNTIL = DISPATCHED_AT.plusDays(7);

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("axon_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark");

    @Test
    void measuresCampaignRunPurchaseLookupWithAndWithoutHistoryIndex() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnvironment();
        Files.createDirectories(config.artifactDir());

        try (Connection connection = openConnection()) {
            createSchema(connection);
            seed(connection, config.totalPurchases());

            String query = rawIncrementalityQuery();
            String withoutIndexPlan = explain(connection, query);
            List<Long> withoutIndexReads = measureReads(connection, query, config.iterations());
            List<String> withoutIndexRows = queryRows(connection, query);
            List<Long> baselineWrites = measureWriteTransactions(
                    connection, config.writeRows(), config.iterations(), 20_000_000L);
            OverlapMeasurement withoutIndexOverlap = measureWritesDuringReadLoad(
                    connection, query, config, 30_000_000L);

            statement(connection, "CREATE INDEX idx_purchase_user_history ON purchases(user_id, purchase_at)");
            String withIndexPlan = explain(connection, query);
            List<Long> withIndexReads = measureReads(connection, query, config.iterations());
            List<String> withIndexRows = queryRows(connection, query);
            OverlapMeasurement withIndexOverlap = measureWritesDuringReadLoad(
                    connection, query, config, 40_000_000L);

            assertThat(withIndexRows).isEqualTo(withoutIndexRows);
            writeArtifacts(config, withoutIndexPlan, withIndexPlan,
                    withoutIndexReads, withIndexReads, baselineWrites, withoutIndexOverlap, withIndexOverlap);
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        statement(connection, """
                CREATE TABLE campaign_activity_run_targets (
                    run_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    execution_id BIGINT NULL,
                    assignment_group VARCHAR(16) NOT NULL,
                    baseline_rfm_segment VARCHAR(16) NULL,
                    PRIMARY KEY (run_id, user_id),
                    UNIQUE KEY uk_campaign_run_execution (execution_id),
                    KEY idx_campaign_run_target_analysis (run_id, assignment_group, baseline_rfm_segment, user_id)
                )
                """);
        statement(connection, """
                CREATE TABLE user_coupons (
                    id BIGINT NOT NULL,
                    marketing_action_execution_id BIGINT NULL,
                    status VARCHAR(20) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_user_coupon_execution (marketing_action_execution_id)
                )
                """);
        statement(connection, """
                CREATE TABLE purchases (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    price DECIMAL(19, 2) NOT NULL,
                    quantity INT NOT NULL,
                    purchase_at DATETIME NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    user_coupon_id BIGINT NULL,
                    UNIQUE KEY uk_purchase_user_coupon (user_coupon_id)
                )
                """);
    }

    private void seed(Connection connection, int totalPurchases) throws SQLException {
        connection.setAutoCommit(false);
        try {
            seedTargets(connection);
            seedUserCoupons(connection);

            String insert = """
                    INSERT INTO purchases(user_id, price, quantity, purchase_at, status, user_coupon_id)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', ?)
                    """;
            int targetPurchases = Math.min(totalPurchases / 5, TARGET_USERS * 2);
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                for (int row = 0; row < totalPurchases; row++) {
                    boolean targetPurchase = row < targetPurchases;
                    long userId = targetPurchase
                            ? (row % TARGET_USERS) + 1L
                            : TARGET_USERS + (row % Math.max(1, totalPurchases / 10)) + 1L;
                    LocalDateTime purchasedAt = targetPurchase
                            ? DISPATCHED_AT.plusHours(row % (30 * 24))
                            : DISPATCHED_AT.minusDays(365).plusHours(row % (400 * 24));
                    statement.setLong(1, userId);
                    statement.setBigDecimal(2, BigDecimal.valueOf(10_000L + (row % 10_000)));
                    statement.setInt(3, 1);
                    statement.setObject(4, purchasedAt);
                    if (targetPurchase && userId <= 30_000 && row < TARGET_USERS) {
                        statement.setLong(5, userId);
                    } else {
                        statement.setObject(5, null);
                    }
                    statement.addBatch();
                    if ((row + 1) % BATCH_SIZE == 0) {
                        executeSeedBatch(connection, statement);
                    }
                }
                if (totalPurchases % BATCH_SIZE != 0) {
                    executeSeedBatch(connection, statement);
                }
            }
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void seedTargets(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO campaign_activity_run_targets
                    (run_id, user_id, execution_id, assignment_group, baseline_rfm_segment)
                VALUES (?, ?, ?, ?, 'LOYAL')
                """)) {
            for (int userId = 1; userId <= TARGET_USERS; userId++) {
                boolean holdout = userId > TARGET_USERS - HOLDOUT_USERS;
                statement.setLong(1, RUN_ID);
                statement.setLong(2, userId);
                if (holdout) {
                    statement.setObject(3, null);
                } else {
                    statement.setLong(3, userId);
                }
                statement.setString(4, holdout ? "HOLDOUT" : "TREATMENT");
                statement.addBatch();
                if (userId % BATCH_SIZE == 0) {
                    executeSeedBatch(connection, statement);
                }
            }
            if (TARGET_USERS % BATCH_SIZE != 0) {
                executeSeedBatch(connection, statement);
            }
        }
    }

    private void seedUserCoupons(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_coupons(id, marketing_action_execution_id, status)
                VALUES (?, ?, ?)
                """)) {
            for (int userId = 1; userId <= 72_000; userId++) {
                statement.setLong(1, userId);
                statement.setLong(2, userId);
                statement.setString(3, userId <= 30_000 ? "USED" : "ISSUED");
                statement.addBatch();
                if (userId % BATCH_SIZE == 0) {
                    executeSeedBatch(connection, statement);
                }
            }
            if (72_000 % BATCH_SIZE != 0) {
                executeSeedBatch(connection, statement);
            }
        }
    }

    private List<Long> measureReads(Connection connection, String query, int iterations) throws SQLException {
        queryRows(connection, query);
        List<Long> elapsed = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            long started = System.nanoTime();
            assertThat(queryRows(connection, query)).hasSize(2);
            elapsed.add((System.nanoTime() - started) / 1_000_000);
        }
        return elapsed;
    }

    private List<String> queryRows(Connection connection, String query) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setObject(1, DISPATCHED_AT);
            statement.setObject(2, OBSERVED_UNTIL);
            statement.setLong(3, RUN_ID);
            try (ResultSet result = statement.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(result.getString("assignment_group") + "|"
                            + result.getLong("target_count") + "|"
                            + result.getLong("purchasing_user_count") + "|"
                            + result.getLong("order_count") + "|"
                            + result.getBigDecimal("revenue") + "|"
                            + result.getLong("issued_coupon_count") + "|"
                            + result.getLong("used_coupon_count") + "|"
                            + result.getLong("coupon_attributed_order_count") + "|"
                            + result.getBigDecimal("coupon_attributed_revenue"));
                }
                return rows;
            }
        }
    }

    private List<Long> measureWriteTransactions(Connection connection, int writeRows, int iterations, long firstUserId)
            throws SQLException {
        List<Long> elapsed = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO purchases(user_id, price, quantity, purchase_at, status, user_coupon_id)
                    VALUES (?, 10000, 1, ?, 'CONFIRMED', NULL)
                    """)) {
                for (int firstRow = 0; firstRow < writeRows; firstRow += 20) {
                    long started = System.nanoTime();
                    int rowsInTransaction = Math.min(20, writeRows - firstRow);
                    for (int row = 0; row < rowsInTransaction; row++) {
                        statement.setLong(1, firstUserId + (long) iteration * writeRows + firstRow + row);
                        // Keep foreground writes outside the seven-day analysis window so both A/B reads use identical data.
                        statement.setObject(2, OBSERVED_UNTIL.plusDays(30L + iteration));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    connection.commit();
                    elapsed.add((System.nanoTime() - started) / 1_000_000);
                }
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return elapsed;
    }

    private OverlapMeasurement measureWritesDuringReadLoad(Connection writeConnection, String query,
                                                            BenchmarkConfig config, long firstUserId) throws Exception {
        AtomicBoolean keepReading = new AtomicBoolean(true);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Queue<Long> analyticsQueryMillis = new ConcurrentLinkedQueue<>();
        CountDownLatch readerReady = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> reader = executor.submit(() -> {
                try (Connection readConnection = openConnection()) {
                    queryRows(readConnection, query); // Same warm-up rule as the standalone read measurement.
                    readerReady.countDown();
                    while (keepReading.get()) {
                        long started = System.nanoTime();
                        queryRows(readConnection, query);
                        analyticsQueryMillis.add((System.nanoTime() - started) / 1_000_000);
                    }
                } catch (Throwable exception) {
                    readerFailure.set(exception);
                    readerReady.countDown();
                }
            });

            assertThat(readerReady.await(30, TimeUnit.SECONDS)).isTrue();
            if (readerFailure.get() != null) {
                throw new IllegalStateException("Could not start background analytics query", readerFailure.get());
            }

            List<Long> writeTransactionMillis = measureWriteTransactions(
                    writeConnection, config.writeRows(), config.iterations(), firstUserId);
            keepReading.set(false);
            reader.get(30, TimeUnit.SECONDS);

            if (readerFailure.get() != null) {
                throw new IllegalStateException("Background analytics query failed", readerFailure.get());
            }
            assertThat(analyticsQueryMillis).isNotEmpty();
            return new OverlapMeasurement(writeTransactionMillis, new ArrayList<>(analyticsQueryMillis));
        }
    }

    private String rawIncrementalityQuery() {
        return """
                SELECT target.assignment_group AS assignment_group,
                       COUNT(DISTINCT target.user_id) AS target_count,
                       COUNT(DISTINCT CASE WHEN purchase.id IS NOT NULL THEN target.user_id END) AS purchasing_user_count,
                       COUNT(purchase.id) AS order_count,
                       COALESCE(SUM(purchase.price * purchase.quantity), 0) AS revenue,
                       COUNT(DISTINCT issued_coupon.id) AS issued_coupon_count,
                       COUNT(DISTINCT CASE WHEN issued_coupon.status = 'USED' THEN issued_coupon.id END) AS used_coupon_count,
                       COALESCE(SUM(CASE WHEN purchase.user_coupon_id = issued_coupon.id
                                         THEN 1 ELSE 0 END), 0) AS coupon_attributed_order_count,
                       COALESCE(SUM(CASE WHEN purchase.user_coupon_id = issued_coupon.id
                                         THEN purchase.price * purchase.quantity ELSE 0 END), 0) AS coupon_attributed_revenue
                FROM campaign_activity_run_targets target
                LEFT JOIN user_coupons issued_coupon
                  ON issued_coupon.marketing_action_execution_id = target.execution_id
                LEFT JOIN purchases purchase
                  ON purchase.user_id = target.user_id
                 AND purchase.status = 'CONFIRMED'
                 AND purchase.purchase_at >= ?
                 AND purchase.purchase_at < ?
                WHERE target.run_id = ?
                GROUP BY target.assignment_group
                ORDER BY target.assignment_group
                """;
    }

    private String explain(Connection connection, String query) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN ANALYZE " + query)) {
            statement.setObject(1, DISPATCHED_AT);
            statement.setObject(2, OBSERVED_UNTIL);
            statement.setLong(3, RUN_ID);
            try (ResultSet result = statement.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (result.next()) {
                    plan.append(result.getString(1)).append(System.lineSeparator());
                }
                return plan.toString();
            }
        }
    }

    private void writeArtifacts(BenchmarkConfig config, String withoutIndexPlan, String withIndexPlan,
                                List<Long> withoutIndexReads, List<Long> withIndexReads,
                                List<Long> baselineWrites, OverlapMeasurement withoutIndexOverlap,
                                OverlapMeasurement withIndexOverlap) throws IOException {
        Files.writeString(config.artifactDir().resolve("explain-without-index.md"), withoutIndexPlan);
        Files.writeString(config.artifactDir().resolve("explain-with-index.md"), withIndexPlan);
        Files.writeString(config.artifactDir().resolve("summary.md"), """
                # Campaign Incrementality Purchase Index Benchmark

                This is a local MySQL query-shape microbenchmark for one campaign Run with 100,000 targets
                (90,000 treatment and 10,000 holdout). It excludes HTTP, Kafka, and application startup.

                The overlap measurement runs campaign-performance reads continuously on a separate MySQL
                connection while 20-row Purchase persistence transactions execute. InnoDB consistent reads do
                not lock those Purchase rows. Any write p95 change is therefore shared database-resource pressure,
                not row-lock waiting. It is not an end-to-end HTTP latency measurement.

                ## Dataset

                - Purchase rows: %,d
                - Campaign targets: 100,000
                - Index A/B: `(user_id, purchase_at)` absent vs present
                - Read iterations: %d
                - Foreground write sample: %d Purchase inserts in transactions of 20

                ## Result (milliseconds)

                | Measurement | Without index | With `(user_id, purchase_at)` |
                | --- | --- | --- |
                | Campaign performance read p50 | %d | %d |
                | Campaign performance read p95 | %d | %d |
                | Background read samples during overlap | %d | %d |
                | Foreground Purchase write p50, no read overlap | %d | same baseline |
                | Foreground Purchase write p95, no read overlap | %d | same baseline |
                | Foreground Purchase write p50, read overlap | %d | %d |
                | Foreground Purchase write p95, read overlap | %d | %d |

                See `explain-without-index.md` and `explain-with-index.md` for the MySQL execution plans.
                """.formatted(
                config.totalPurchases(), config.iterations(), config.writeRows(),
                median(withoutIndexReads), median(withIndexReads),
                p95(withoutIndexReads), p95(withIndexReads),
                withoutIndexOverlap.analyticsQueryMillis().size(), withIndexOverlap.analyticsQueryMillis().size(),
                median(baselineWrites), p95(baselineWrites),
                median(withoutIndexOverlap.writeTransactionMillis()), median(withIndexOverlap.writeTransactionMillis()),
                p95(withoutIndexOverlap.writeTransactionMillis()), p95(withIndexOverlap.writeTransactionMillis())));
    }

    private long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private long p95(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }

    private void statement(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeSeedBatch(Connection connection, PreparedStatement statement) throws SQLException {
        statement.executeBatch();
        connection.commit();
    }

    private Connection openConnection() throws SQLException {
        String separator = mysql.getJdbcUrl().contains("?") ? "&" : "?";
        return DriverManager.getConnection(mysql.getJdbcUrl() + separator + "rewriteBatchedStatements=true",
                mysql.getUsername(), mysql.getPassword());
    }

    private record BenchmarkConfig(int totalPurchases, int iterations, int writeRows, Path artifactDir) {
        private static BenchmarkConfig fromEnvironment() {
            return new BenchmarkConfig(
                    integer("campaign.incrementality.benchmark.purchases", 1_000_000),
                    integer("campaign.incrementality.benchmark.iterations", 3),
                    integer("campaign.incrementality.benchmark.write-rows", 800),
                    Path.of(System.getProperty("campaign.incrementality.benchmark.artifact-dir",
                            "artifacts/benchmark/campaign-run-incrementality")));
        }

        private static int integer(String name, int defaultValue) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        }
    }

    private record OverlapMeasurement(List<Long> writeTransactionMillis, List<Long> analyticsQueryMillis) {
    }
}
