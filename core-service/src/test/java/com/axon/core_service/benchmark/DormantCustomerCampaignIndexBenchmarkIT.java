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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("benchmark")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "dormant.campaign.benchmark", matches = "true")
class DormantCustomerCampaignIndexBenchmarkIT {

    private static final int SUMMARY_ROWS = 1_000_000;
    private static final int DORMANT_ROWS = 50_000;
    private static final int EXECUTED_DORMANT_ROWS = 10_000;
    private static final int READ_ITERATIONS = 5;
    private static final int WRITE_ROWS = 1_000;
    private static final long CAMPAIGN_ID = 77L;

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("axon_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark");

    @Test
    void measuresReverseLookupPlanAndLastPurchaseWriteCost() throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            createSchema(connection);
            seed(connection);

            String query = """
                    SELECT s.user_id
                    FROM user_summary s
                    WHERE s.last_purchase_at < ?
                      AND NOT EXISTS (
                          SELECT 1 FROM marketing_action_executions e
                          WHERE e.reengagement_campaign_id = ? AND e.user_id = s.user_id
                      )
                    ORDER BY s.last_purchase_at ASC, s.user_id ASC
                    LIMIT 500
                    """;
            LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
            String baselinePlan = explain(connection, query, cutoff);
            List<Long> baselineRead = measureReads(connection, query, cutoff);

            statement(connection, "CREATE INDEX idx_user_summary_last_purchase_user ON user_summary(last_purchase_at, user_id)");
            String indexedPlan = explain(connection, query, cutoff);
            List<Long> indexedRead = measureReads(connection, query, cutoff);

            createWriteTables(connection);
            List<Long> baseWrites = measureUpdates(connection, "user_summary_write_base");
            List<Long> indexedWrites = measureUpdates(connection, "user_summary_write_indexed");
            writeArtifacts(baselinePlan, indexedPlan, baselineRead, indexedRead, baseWrites, indexedWrites);

            assertThat(median(indexedRead)).isLessThanOrEqualTo(median(baselineRead));
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        statement(connection, "CREATE TABLE user_summary (user_id BIGINT PRIMARY KEY, last_purchase_at DATETIME NULL)");
        statement(connection, "CREATE TABLE marketing_action_executions (reengagement_campaign_id BIGINT NULL, user_id BIGINT NOT NULL, UNIQUE KEY uk_campaign_user (reengagement_campaign_id, user_id))");
    }

    private void seed(Connection connection) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        for (int firstId = 1; firstId <= SUMMARY_ROWS; firstId += 1_000) {
            int rowCount = Math.min(1_000, SUMMARY_ROWS - firstId + 1);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO user_summary(user_id, last_purchase_at) VALUES " + values(rowCount, 2))) {
                for (int offset = 0; offset < rowCount; offset++) {
                    int id = firstId + offset;
                    insert.setLong(offset * 2 + 1, id);
                    insert.setObject(offset * 2 + 2,
                            id <= DORMANT_ROWS ? now.minusDays(120).plusMinutes(id % 60) : now.minusDays(id % 30));
                }
                insert.executeUpdate();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO marketing_action_executions(reengagement_campaign_id, user_id) VALUES "
                        + values(EXECUTED_DORMANT_ROWS, 2))) {
            for (int id = 1; id <= EXECUTED_DORMANT_ROWS; id++) {
                insert.setLong((id - 1) * 2 + 1, CAMPAIGN_ID);
                insert.setLong((id - 1) * 2 + 2, id);
            }
            insert.executeUpdate();
        }
    }

    private List<Long> measureReads(Connection connection, String query, LocalDateTime cutoff) throws SQLException {
        runQuery(connection, query, cutoff);
        List<Long> elapsed = new ArrayList<>();
        for (int i = 0; i < READ_ITERATIONS; i++) {
            long started = System.nanoTime();
            assertThat(runQuery(connection, query, cutoff)).isEqualTo(500);
            elapsed.add((System.nanoTime() - started) / 1_000_000);
        }
        return elapsed;
    }

    private int runQuery(Connection connection, String query, LocalDateTime cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setObject(1, cutoff);
            statement.setLong(2, CAMPAIGN_ID);
            try (ResultSet result = statement.executeQuery()) {
                int count = 0;
                while (result.next()) count++;
                return count;
            }
        }
    }

    private String explain(Connection connection, String query, LocalDateTime cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN ANALYZE " + query)) {
            statement.setObject(1, cutoff);
            statement.setLong(2, CAMPAIGN_ID);
            try (ResultSet result = statement.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (result.next()) plan.append(result.getString(1)).append(System.lineSeparator());
                return plan.toString();
            }
        }
    }

    private void createWriteTables(Connection connection) throws SQLException {
        statement(connection, "CREATE TABLE user_summary_write_base LIKE user_summary");
        statement(connection, "CREATE TABLE user_summary_write_indexed LIKE user_summary");
        statement(connection, "ALTER TABLE user_summary_write_base DROP INDEX idx_user_summary_last_purchase_user");
        statement(connection, "INSERT INTO user_summary_write_base SELECT * FROM user_summary");
        statement(connection, "INSERT INTO user_summary_write_indexed SELECT * FROM user_summary");
    }

    private List<Long> measureUpdates(Connection connection, String table) throws SQLException {
        List<Long> elapsed = new ArrayList<>();
        for (int run = 0; run < READ_ITERATIONS; run++) {
            long started = System.nanoTime();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + table + " SET last_purchase_at = ? WHERE user_id = ?")) {
                for (int id = 1; id <= WRITE_ROWS; id++) {
                    update.setObject(1, LocalDateTime.now().plusDays(run + 1));
                    update.setLong(2, 900_000L + id);
                    update.addBatch();
                }
                update.executeBatch();
            }
            elapsed.add((System.nanoTime() - started) / 1_000_000);
        }
        return elapsed;
    }

    private void writeArtifacts(String baselinePlan, String indexedPlan, List<Long> baselineRead,
                                List<Long> indexedRead, List<Long> baseWrites, List<Long> indexedWrites)
            throws IOException {
        Path directory = Path.of(System.getProperty("dormant.campaign.benchmark.artifact-dir",
                "artifacts/benchmark/dormant-customer-campaign"));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("explain-without-index.md"), baselinePlan);
        Files.writeString(directory.resolve("explain-with-index.md"), indexedPlan);
        Files.writeString(directory.resolve("summary.md"), """
                Dataset: 1,000,000 user_summary rows, 50,000 dormant candidates, 10,000 existing executions.
                Read milliseconds: without=%s, with=%s, median=%d -> %d
                Update milliseconds for 1,000 last_purchase_at changes: without=%s, with=%s, median=%d -> %d
                """.formatted(baselineRead, indexedRead, median(baselineRead), median(indexedRead),
                baseWrites, indexedWrites, median(baseWrites), median(indexedWrites)));
    }

    private long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private void statement(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String values(int rows, int columns) {
        StringBuilder values = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            if (row > 0) values.append(',');
            values.append('(');
            for (int column = 0; column < columns; column++) {
                if (column > 0) values.append(',');
                values.append('?');
            }
            values.append(')');
        }
        return values.toString();
    }
}
