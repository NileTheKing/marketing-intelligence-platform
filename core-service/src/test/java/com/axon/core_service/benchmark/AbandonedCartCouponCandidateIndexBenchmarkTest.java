package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Opt-in MySQL benchmark for the scheduler's due-candidate repository query.
 * It isolates the MySQL lookup and state writes from Kafka and HTTP.
 */
@Tag("benchmark")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "abandoned.cart.candidate.benchmark", matches = "true")
class AbandonedCartCouponCandidateIndexBenchmarkTest {

    private static final int BATCH_SIZE = 5_000;
    private static final int FETCH_SIZE = 500;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("axon_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark");

    @Test
    void comparesDueCandidateLookupAndStateWritesWithAndWithoutCompositeIndex() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        Files.createDirectories(config.artifactDir());

        Properties properties = new Properties();
        properties.setProperty("user", mysql.getUsername());
        properties.setProperty("password", mysql.getPassword());
        properties.setProperty("rewriteBatchedStatements", "true");
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), properties)) {
            createSchema(connection);
            seedCampaigns(connection);
            seedCandidates(connection, config.rows());
            analyzeQueryTables(connection);
            Files.writeString(config.artifactDir().resolve("dataset-and-statistics.txt"),
                    datasetAndStatistics(connection));

            String withoutIndexPlan = explain(connection);
            List<Long> withoutIndexReads = measureReads(connection, config.iterations());
            List<Long> expectedCandidates = readDueCandidates(connection);

            execute(connection, "CREATE INDEX idx_abandoned_cart_candidate_due ON abandoned_cart_coupon_candidates(status, due_at, id)");
            analyzeQueryTables(connection);

            String withIndexPlan = explain(connection);
            List<Long> withIndexReads = measureReads(connection, config.iterations());
            assertThat(readDueCandidates(connection)).containsExactlyElementsOf(expectedCandidates);

            execute(connection, "DROP INDEX idx_abandoned_cart_candidate_due ON abandoned_cart_coupon_candidates");
            execute(connection, "CREATE INDEX idx_abandoned_cart_candidate_campaign_due "
                    + "ON abandoned_cart_coupon_candidates(campaign_id, status, due_at, id)");
            analyzeQueryTables(connection);
            String campaignIndexPlan = explain(connection);
            List<Long> campaignIndexReads = measureReads(connection, config.iterations());
            assertThat(readDueCandidates(connection)).containsExactlyElementsOf(expectedCandidates);
            WriteMeasurement withoutIndexWrites = measureWrites(connection,
                    "abandoned_cart_candidate_writes_without_index", config.writeRows(), config.iterations(), 10_000_000L);
            WriteMeasurement withIndexWrites = measureWrites(connection,
                    "abandoned_cart_candidate_writes_with_index", config.writeRows(), config.iterations(), 20_000_000L);
            WriteMeasurement campaignIndexWrites = measureWrites(connection,
                    "abandoned_cart_candidate_writes_campaign_index", config.writeRows(), config.iterations(), 30_000_000L);

            assertThat(readDueCandidates(connection)).hasSize(FETCH_SIZE);
            writeArtifacts(config, withoutIndexPlan, withIndexPlan, withoutIndexReads, withIndexReads,
                    withoutIndexWrites, withIndexWrites);
            Files.writeString(config.artifactDir().resolve("abandoned-cart-candidate-explain-campaign-index.txt"),
                    campaignIndexPlan);
            Files.writeString(config.artifactDir().resolve("campaign-leading-index-comparison.md"), """
                    # Same joined query, three index variants

                    - rows: %d; fetch size: %d; iterations: %d
                    - Identical ordered candidate IDs asserted across all variants.
                    - JDBC rewriteBatchedStatements=true for all variants, including write measurements.
                    - Write batches: %d rows, separate initially empty tables; not the 5M-row lookup table.

                    | Measurement (ms) | No due index | (status, due_at, id) | (campaign_id, status, due_at, id) |
                    | --- | --- | --- | --- |
                    | Joined query | %s | %s | %s |
                    | Insert | %s | %s | %s |
                    | SCHEDULED -> CANCELLED | %s | %s | %s |
                    """.formatted(config.rows(), FETCH_SIZE, config.iterations(), config.writeRows(),
                    withoutIndexReads, withIndexReads, campaignIndexReads,
                    withoutIndexWrites.insertMillis(), withIndexWrites.insertMillis(), campaignIndexWrites.insertMillis(),
                    withoutIndexWrites.transitionMillis(), withIndexWrites.transitionMillis(), campaignIndexWrites.transitionMillis()));
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE marketing_actions (
                    id BIGINT NOT NULL,
                    action_type VARCHAR(20) NOT NULL,
                    created_at DATETIME NULL,
                    reference_id BIGINT NOT NULL,
                    is_active BOOLEAN NOT NULL,
                    marketing_rule_id BIGINT NULL,
                    updated_at DATETIME NULL,
                    PRIMARY KEY (id)
                )
                """);
        execute(connection, """
                CREATE TABLE abandoned_cart_coupon_campaigns (
                    id BIGINT NOT NULL,
                    marketing_action_id BIGINT NOT NULL,
                    delay_minutes INT NOT NULL,
                    is_active BOOLEAN NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_abandoned_cart_coupon_campaign_action (marketing_action_id)
                )
                """);
        createCandidateTable(connection, "abandoned_cart_coupon_candidates");
        createCandidateTable(connection, "abandoned_cart_candidate_writes_without_index");
        createCandidateTable(connection, "abandoned_cart_candidate_writes_with_index");
        createCandidateTable(connection, "abandoned_cart_candidate_writes_campaign_index");
        execute(connection, "CREATE INDEX idx_abandoned_cart_candidate_writes_due "
                + "ON abandoned_cart_candidate_writes_with_index(status, due_at, id)");
        execute(connection, "CREATE INDEX idx_abandoned_cart_candidate_writes_campaign_due "
                + "ON abandoned_cart_candidate_writes_campaign_index(campaign_id, status, due_at, id)");
    }

    private void createCandidateTable(Connection connection, String tableName) throws SQLException {
        execute(connection, """
                CREATE TABLE %s (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    campaign_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    last_cart_activity_at DATETIME NOT NULL,
                    due_at DATETIME NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    claimed_at DATETIME NULL,
                    marketing_action_execution_id BIGINT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_candidate (campaign_id, user_id, product_id)
                )
                """.formatted(tableName));
    }

    private void seedCampaigns(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement action = connection.prepareStatement("""
                INSERT INTO marketing_actions(
                    id, action_type, created_at, reference_id, is_active, marketing_rule_id, updated_at
                ) VALUES (?, 'COUPON', ?, ?, true, null, ?)
                """);
             PreparedStatement campaign = connection.prepareStatement("""
                INSERT INTO abandoned_cart_coupon_campaigns(id, marketing_action_id, delay_minutes, is_active)
                VALUES (?, ?, 30, true)
                """)) {
            for (long id = 1; id <= 20; id++) {
                action.setLong(1, id);
                action.setObject(2, NOW.minusDays(1));
                action.setLong(3, 1_000 + id);
                action.setObject(4, NOW.minusDays(1));
                action.addBatch();
                campaign.setLong(1, id);
                campaign.setLong(2, id);
                campaign.addBatch();
            }
            action.executeBatch();
            campaign.executeBatch();
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void seedCandidates(Connection connection, int rows) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO abandoned_cart_coupon_candidates(
                    campaign_id, user_id, product_id, last_cart_activity_at, due_at, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (int row = 0; row < rows; row++) {
                int bucket = row % 100;
                String status;
                LocalDateTime dueAt;
                if (bucket == 0) {
                    status = "SCHEDULED";
                    dueAt = NOW.minusMinutes(1 + (row % 1_440));
                } else if (bucket < 5) {
                    status = "SCHEDULED";
                    dueAt = NOW.plusMinutes(1 + (row % 43_200));
                } else if (bucket < 15) {
                    status = "CANCELLED";
                    dueAt = NOW.minusDays(30).plusMinutes(row % 43_200);
                } else {
                    status = "DISPATCHED";
                    dueAt = NOW.minusDays(365).plusMinutes(row % 525_600);
                }
                // Rotate campaigns independently of the status bucket.
                statement.setLong(1, 1L + ((row / 100) % 20));
                statement.setLong(2, row + 1L);
                statement.setLong(3, 1L + (row % 1_000));
                statement.setObject(4, dueAt.minusMinutes(30));
                statement.setObject(5, dueAt);
                statement.setString(6, status);
                statement.addBatch();
                if ((row + 1) % BATCH_SIZE == 0) {
                    executeSeedBatch(connection, statement);
                }
            }
            if (rows % BATCH_SIZE != 0) {
                executeSeedBatch(connection, statement);
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private List<Long> measureReads(Connection connection, int iterations) throws SQLException {
        readDueCandidates(connection); // buffer warm-up
        List<Long> elapsedMillis = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            long started = System.nanoTime();
            assertThat(readDueCandidates(connection)).hasSize(FETCH_SIZE);
            elapsedMillis.add((System.nanoTime() - started) / 1_000_000);
        }
        return elapsedMillis;
    }

    private List<Long> readDueCandidates(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT candidate.id,
                       candidate.campaign_id,
                       campaign.id,
                       campaign.delay_minutes,
                       campaign.is_active,
                       campaign.marketing_action_id,
                       action.id,
                       action.action_type,
                       action.created_at,
                       action.is_active,
                       action.marketing_rule_id,
                       action.reference_id,
                       action.updated_at,
                       candidate.claimed_at,
                       candidate.due_at,
                       candidate.last_cart_activity_at,
                       candidate.marketing_action_execution_id,
                       candidate.product_id,
                       candidate.status,
                       candidate.user_id
                FROM abandoned_cart_coupon_candidates candidate
                JOIN abandoned_cart_coupon_campaigns campaign ON campaign.id = candidate.campaign_id
                JOIN marketing_actions action ON action.id = campaign.marketing_action_id
                WHERE candidate.status = 'SCHEDULED' AND candidate.due_at <= ?
                ORDER BY candidate.due_at, candidate.id
                LIMIT 500
                """)) {
            statement.setObject(1, NOW);
            try (ResultSet result = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getLong(1));
                    for (int column = 2; column <= 20; column++) {
                        result.getObject(column);
                    }
                }
                return ids;
            }
        }
    }

    private WriteMeasurement measureWrites(Connection connection, String tableName, int rows, int iterations,
                                           long firstUserId)
            throws SQLException {
        List<Long> insertMillis = new ArrayList<>();
        List<Long> transitionMillis = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            long userOffset = firstUserId + (long) iteration * rows;
            long insertStarted = System.nanoTime();
            insertWriteBatch(connection, tableName, userOffset, rows);
            insertMillis.add((System.nanoTime() - insertStarted) / 1_000_000);

            long transitionStarted = System.nanoTime();
            transitionWriteBatch(connection, tableName, userOffset, rows);
            transitionMillis.add((System.nanoTime() - transitionStarted) / 1_000_000);
        }
        return new WriteMeasurement(insertMillis, transitionMillis);
    }

    private void insertWriteBatch(Connection connection, String tableName, long firstUserId, int rows) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s(campaign_id, user_id, product_id, last_cart_activity_at, due_at, status)
                VALUES (999, ?, ?, ?, ?, 'SCHEDULED')
                """.formatted(tableName))) {
            for (int row = 0; row < rows; row++) {
                long userId = firstUserId + row;
                statement.setLong(1, userId);
                statement.setLong(2, userId);
                statement.setObject(3, NOW.plusMinutes(30));
                statement.setObject(4, NOW.plusHours(1));
                statement.addBatch();
                if ((row + 1) % BATCH_SIZE == 0) {
                    executeSeedBatch(connection, statement);
                }
            }
            if (rows % BATCH_SIZE != 0) {
                executeSeedBatch(connection, statement);
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void transitionWriteBatch(Connection connection, String tableName, long firstUserId, int rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE %s
                SET status = 'CANCELLED'
                WHERE campaign_id = 999 AND user_id >= ? AND user_id < ?
                """.formatted(tableName))) {
            statement.setLong(1, firstUserId);
            statement.setLong(2, firstUserId + rows);
            assertThat(statement.executeUpdate()).isEqualTo(rows);
        }
    }

    private String explain(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     EXPLAIN ANALYZE
                     SELECT candidate.id,
                            candidate.campaign_id,
                            campaign.id,
                            campaign.delay_minutes,
                            campaign.is_active,
                            campaign.marketing_action_id,
                            action.id,
                            action.action_type,
                            action.created_at,
                            action.is_active,
                            action.marketing_rule_id,
                            action.reference_id,
                            action.updated_at,
                            candidate.claimed_at,
                            candidate.due_at,
                            candidate.last_cart_activity_at,
                            candidate.marketing_action_execution_id,
                            candidate.product_id,
                            candidate.status,
                            candidate.user_id
                     FROM abandoned_cart_coupon_candidates candidate
                     JOIN abandoned_cart_coupon_campaigns campaign ON campaign.id = candidate.campaign_id
                     JOIN marketing_actions action ON action.id = campaign.marketing_action_id
                     WHERE candidate.status = 'SCHEDULED'
                       AND candidate.due_at <= '2026-10-14 12:00:00'
                     ORDER BY candidate.due_at, candidate.id
                     LIMIT 500
                     """)) {
            StringBuilder plan = new StringBuilder();
            while (result.next()) {
                plan.append(result.getString(1)).append(System.lineSeparator());
            }
            return plan.toString();
        }
    }

    private void executeSeedBatch(Connection connection, PreparedStatement statement) throws SQLException {
        statement.executeBatch();
        statement.clearBatch();
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void analyzeTable(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("ANALYZE TABLE " + tableName)) {
            while (result.next()) {
                assertThat(result.getString("Msg_type")).isEqualTo("status");
                assertThat(result.getString("Msg_text")).isEqualTo("OK");
            }
        }
    }

    private void analyzeQueryTables(Connection connection) throws SQLException {
        analyzeTable(connection, "marketing_actions");
        analyzeTable(connection, "abandoned_cart_coupon_campaigns");
        analyzeTable(connection, "abandoned_cart_coupon_candidates");
    }

    private String datasetAndStatistics(Connection connection) throws SQLException {
        StringBuilder output = new StringBuilder();
        for (String query : List.of(
                "SELECT VERSION() AS mysql_version",
                "SELECT campaign_id, COUNT(*) AS total_rows, "
                        + "SUM(status = 'SCHEDULED' AND due_at <= '2026-10-14 12:00:00') AS due_rows "
                        + "FROM abandoned_cart_coupon_candidates GROUP BY campaign_id ORDER BY campaign_id",
                "SHOW INDEX FROM abandoned_cart_coupon_candidates",
                "SHOW INDEX FROM abandoned_cart_coupon_campaigns",
                "SHOW INDEX FROM marketing_actions")) {
            output.append(query).append(System.lineSeparator());
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(query)) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    for (int column = 1; column <= columns; column++) {
                        output.append(result.getMetaData().getColumnLabel(column)).append('=')
                                .append(result.getObject(column)).append(' ');
                    }
                    output.append(System.lineSeparator());
                }
            }
        }
        return output.toString();
    }

    private void writeArtifacts(BenchmarkConfig config, String withoutIndexPlan, String withIndexPlan,
                                List<Long> withoutIndexReads, List<Long> withIndexReads,
                                WriteMeasurement withoutIndexWrites, WriteMeasurement withIndexWrites)
            throws IOException {
        Files.writeString(config.artifactDir().resolve("abandoned-cart-candidate-explain-without-index.txt"),
                withoutIndexPlan);
        Files.writeString(config.artifactDir().resolve("abandoned-cart-candidate-explain-with-index.txt"),
                withIndexPlan);
        Files.writeString(config.artifactDir().resolve("abandoned-cart-candidate-index-benchmark.md"), """
                # Abandoned-cart candidate index benchmark

                - rows: %d
                - query iterations: %d
                - write rows per iteration: %d
                - due-candidate fetch size: %d
                - Query: full candidate fields joined with campaign and marketing action; excludes JPA hydration and Kafka.
                - Distribution: status buckets and campaign assignment are independent; see dataset-and-statistics.txt.
                - Statistics: ANALYZE TABLE completed successfully for all three joined tables before each variant.
                - JDBC rewriteBatchedStatements=true for all variants; write times are not comparable to older runs without it.

                | measurement | without composite index | with `(status, due_at, id)` |
                | --- | --- | --- |
                | due query ms | %s | %s |
                | candidate insert ms | %s | %s |
                | `SCHEDULED -> CANCELLED` ms | %s | %s |
                """.formatted(config.rows(), config.iterations(), config.writeRows(), FETCH_SIZE,
                withoutIndexReads, withIndexReads,
                withoutIndexWrites.insertMillis(), withIndexWrites.insertMillis(),
                withoutIndexWrites.transitionMillis(), withIndexWrites.transitionMillis()));
    }

    private record WriteMeasurement(List<Long> insertMillis, List<Long> transitionMillis) {
    }

    private record BenchmarkConfig(Path artifactDir, int rows, int iterations, int writeRows) {
        private static BenchmarkConfig fromSystemProperties() {
            return new BenchmarkConfig(
                    Path.of(System.getProperty("abandoned.cart.candidate.benchmark.artifact-dir",
                            "build/benchmark/abandoned-cart-candidate")),
                    Integer.getInteger("abandoned.cart.candidate.benchmark.rows", 1_000_000),
                    Integer.getInteger("abandoned.cart.candidate.benchmark.iterations", 5),
                    Integer.getInteger("abandoned.cart.candidate.benchmark.write-rows", 10_000));
        }
    }
}
