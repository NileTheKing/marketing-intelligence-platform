package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.purchase.Purchase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Deliberately opt-in. This compares the old aggregation shape with the current SQL shape,
 * not the full scheduler or unrelated application startup cost.
 */
@Tag("benchmark")
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "cohort.sql.benchmark", matches = "true")
class CohortSqlOffloadingBenchmarkIT {

    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 7, 1, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 8, 1, 0, 0);

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
    private NamedParameterJdbcTemplate namedJdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void comparesLegacyEntityHydrationWithSqlAggregation() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnvironment();
        Path artifactDir = config.artifactDir();
        Files.createDirectories(artifactDir);

        seed(config);
        List<Long> cohortUserIds = java.util.stream.LongStream.rangeClosed(1, config.cohortUsers())
                .boxed()
                .toList();

        // Warm the SQL/JPA metadata and MySQL buffer pool. Warm-up times are not reported.
        assertEquivalent(legacyAggregate(cohortUserIds), sqlAggregate(cohortUserIds));
        entityManager.clear();

        List<RunResult> legacyRuns = new ArrayList<>();
        List<RunResult> sqlRuns = new ArrayList<>();
        for (int iteration = 1; iteration <= config.iterations(); iteration++) {
            boolean captureJfr = iteration == config.iterations();
            if (iteration % 2 == 1) {
                legacyRuns.add(measure("legacy-" + iteration, () -> legacyAggregate(cohortUserIds), artifactDir, captureJfr));
                entityManager.clear();
                sqlRuns.add(measure("sql-" + iteration, () -> sqlAggregate(cohortUserIds), artifactDir, captureJfr));
            } else {
                sqlRuns.add(measure("sql-" + iteration, () -> sqlAggregate(cohortUserIds), artifactDir, captureJfr));
                legacyRuns.add(measure("legacy-" + iteration, () -> legacyAggregate(cohortUserIds), artifactDir, captureJfr));
                entityManager.clear();
            }
        }

        assertEquivalent(legacyRuns.getLast().aggregate(), sqlRuns.getLast().aggregate());
        writeReport(artifactDir, config, legacyRuns, sqlRuns, cohortUserIds);
    }

    private void seed(BenchmarkConfig config) {
        JdbcTemplate jdbc = namedJdbc.getJdbcTemplate();
        jdbc.update("DELETE FROM purchases");

        String insert = """
                INSERT INTO purchases
                    (user_id, product_id, campaign_activity_id, purchase_type, price, quantity, purchase_at, status)
                VALUES (?, ?, NULL, 'SHOP', ?, 1, ?, 'CONFIRMED')
                """;
        List<Object[]> rows = new ArrayList<>(1_000);
        int totalRows = config.cohortUsers() * config.purchasesPerCohortUser() + config.unrelatedPurchases();
        for (int row = 0; row < totalRows; row++) {
            boolean cohortPurchase = row < config.cohortUsers() * config.purchasesPerCohortUser();
            long userId = cohortPurchase
                    ? (row % config.cohortUsers()) + 1L
                    : config.cohortUsers() + (row % config.unrelatedUsers()) + 1L;
            LocalDateTime purchasedAt = PERIOD_START.minusMonths(11).plusDays(row % 396).plusHours(row % 24);
            rows.add(new Object[] {userId, 1L, BigDecimal.valueOf(10_000L + (row % 10)), purchasedAt});
            if (rows.size() == 1_000) {
                jdbc.batchUpdate(insert, rows);
                rows.clear();
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(insert, rows);
        }
    }

    private Aggregate legacyAggregate(List<Long> userIds) {
        List<Purchase> purchases = entityManager.createQuery("""
                        SELECT p FROM Purchase p
                        WHERE p.userId IN :userIds
                        ORDER BY p.userId, p.purchaseAt
                        """, Purchase.class)
                .setParameter("userIds", userIds)
                .getResultList();
        BigDecimal monthlyRevenue = BigDecimal.ZERO;
        BigDecimal cumulativeRevenue = BigDecimal.ZERO;
        int monthlyOrders = 0;
        Map<Long, UserAggregate> userAggregates = new HashMap<>();

        for (Purchase purchase : purchases) {
            if (!purchase.getPurchaseAt().isBefore(PERIOD_END)) {
                continue;
            }
            BigDecimal value = purchase.getPrice().multiply(BigDecimal.valueOf(purchase.getQuantity()));
            cumulativeRevenue = cumulativeRevenue.add(value);
            UserAggregate userAggregate = userAggregates.computeIfAbsent(purchase.getUserId(), ignored -> new UserAggregate());
            userAggregate.count++;
            userAggregate.revenue = userAggregate.revenue.add(value);
            if (!purchase.getPurchaseAt().isBefore(PERIOD_START)) {
                monthlyRevenue = monthlyRevenue.add(value);
                monthlyOrders++;
                userAggregate.activeInPeriod = true;
            }
        }

        long activeUsers = userAggregates.values().stream().filter(aggregate -> aggregate.activeInPeriod).count();
        long repeatUsers = userAggregates.values().stream().filter(aggregate -> aggregate.count > 1).count();
        int totalOrders = userAggregates.values().stream().mapToInt(aggregate -> aggregate.count).sum();
        return new Aggregate(monthlyRevenue, monthlyOrders, activeUsers, cumulativeRevenue,
                ratio(repeatUsers, userAggregates.size()), ratio(totalOrders, userAggregates.size()),
                ratio(cumulativeRevenue, totalOrders));
    }

    private Aggregate sqlAggregate(List<Long> userIds) {
        MapSqlParameterSource monthlyParams = params(userIds).addValue("start", PERIOD_START).addValue("end", PERIOD_END);
        Map<String, Object> monthly = namedJdbc.queryForMap("""
                SELECT COALESCE(SUM(price * quantity), 0) AS revenue,
                       COUNT(*) AS orders,
                       COUNT(DISTINCT user_id) AS active_users
                FROM purchases
                WHERE user_id IN (:userIds)
                  AND status = 'CONFIRMED'
                  AND purchase_at >= :start
                  AND purchase_at < :end
                """, monthlyParams);
        MapSqlParameterSource untilParams = params(userIds).addValue("until", PERIOD_END);
        Map<String, Object> cumulative = namedJdbc.queryForMap("""
                SELECT COALESCE(SUM(price * quantity), 0) AS revenue
                FROM purchases
                WHERE user_id IN (:userIds)
                  AND status = 'CONFIRMED'
                  AND purchase_at < :until
                """, untilParams);
        Map<String, Object> repeat = namedJdbc.queryForMap("""
                SELECT COUNT(DISTINCT CASE WHEN purchase_count > 1 THEN user_id END) AS repeat_users,
                       COUNT(DISTINCT user_id) AS users,
                       SUM(purchase_count) AS orders
                FROM (
                    SELECT user_id, COUNT(*) AS purchase_count
                    FROM purchases
                    WHERE user_id IN (:userIds)
                      AND status = 'CONFIRMED'
                      AND purchase_at < :until
                    GROUP BY user_id
                ) AS user_agg
                """, untilParams);
        long users = number(repeat, "users").longValue();
        long orders = number(repeat, "orders").longValue();
        BigDecimal revenue = decimal(cumulative, "revenue");
        return new Aggregate(decimal(monthly, "revenue"), number(monthly, "orders").longValue(),
                number(monthly, "active_users").longValue(), revenue,
                ratio(number(repeat, "repeat_users").longValue(), users), ratio(orders, users), ratio(revenue, orders));
    }

    private RunResult measure(String name, AggregateSupplier supplier, Path artifactDir, boolean captureJfr) throws Exception {
        Recording recording = captureJfr ? new Recording() : null;
        if (recording != null) {
            recording.enable("jdk.ObjectAllocationInNewTLAB");
            recording.enable("jdk.ObjectAllocationOutsideTLAB");
            recording.enable("jdk.GarbageCollection");
            recording.start();
        }
        long started = System.nanoTime();
        Aggregate aggregate = supplier.get();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        if (recording != null) {
            recording.stop();
            recording.dump(artifactDir.resolve(name + ".jfr"));
            recording.close();
        }
        return new RunResult(name, elapsedMillis, aggregate);
    }

    private void writeReport(
            Path artifactDir,
            BenchmarkConfig config,
            List<RunResult> legacyRuns,
            List<RunResult> sqlRuns,
            List<Long> userIds) throws Exception {
        String report = """
                # Cohort SQL Offloading Benchmark

                This is a query-shape microbenchmark. It compares historical Purchase entity hydration + Java aggregation
                with the current SQL aggregate shape. It is not a full scheduler, HTTP, or production-load benchmark.

                ## Dataset

                - Profile: %s
                - Cohort users: %d
                - Purchases per cohort user: %d
                - Unrelated purchases: %d
                - Total purchases: %d
                - Cohort purchase share: %.2f%%
                - Measured iterations per approach: %d

                ## Timing (ms)

                | Approach | Runs | Median |
                | --- | --- | --- |
                | Legacy entity hydration + Java aggregation | %s | %d |
                | SQL aggregation | %s | %d |

                ## Artifacts

                - `legacy-%d.jfr`: allocation and GC events for the final legacy run
                - `sql-%d.jfr`: allocation and GC events for the final SQL run
                - `explain.md`: MySQL `EXPLAIN ANALYZE` output for the legacy lookup and SQL aggregate lookup

                Do not claim a performance percentage until this report is reproduced with a recorded environment and
                stable results. JFR files require Java Mission Control or another JFR reader for allocation/GC analysis.
                """.formatted(
                config.profile(), config.cohortUsers(), config.purchasesPerCohortUser(), config.unrelatedPurchases(), config.totalPurchases(), config.cohortPurchaseShare(),
                config.iterations(), durations(legacyRuns), median(legacyRuns), durations(sqlRuns), median(sqlRuns),
                config.iterations(), config.iterations());
        Files.writeString(artifactDir.resolve("summary.md"), report);
        Files.writeString(artifactDir.resolve("explain.md"), explain(userIds));
    }

    private String explain(List<Long> userIds) {
        MapSqlParameterSource params = params(userIds).addValue("start", PERIOD_START).addValue("end", PERIOD_END);
        List<Map<String, Object>> legacyPlan = namedJdbc.queryForList("""
                EXPLAIN ANALYZE
                SELECT * FROM purchases
                WHERE user_id IN (:userIds)
                ORDER BY user_id, purchase_at
                """, params);
        List<Map<String, Object>> sqlPlan = namedJdbc.queryForList("""
                EXPLAIN ANALYZE
                SELECT COALESCE(SUM(price * quantity), 0), COUNT(*), COUNT(DISTINCT user_id)
                FROM purchases
                WHERE user_id IN (:userIds)
                  AND status = 'CONFIRMED'
                  AND purchase_at >= :start
                  AND purchase_at < :end
                """, params);
        return "# EXPLAIN ANALYZE\n\n## Legacy entity lookup\n\n```\n" + formatPlan(legacyPlan)
                + "\n```\n\n## SQL monthly aggregate\n\n```\n" + formatPlan(sqlPlan) + "\n```\n";
    }

    private String formatPlan(List<Map<String, Object>> plan) {
        // MySQL expands the 10,000-value IN list in its text plan; keep the plan readable in artifacts.
        return plan.toString().replaceAll("(?i)in \\(.*?\\)", "IN (:userIds)");
    }

    private void assertEquivalent(Aggregate left, Aggregate right) {
        assertThat(left.monthlyRevenue()).isEqualByComparingTo(right.monthlyRevenue());
        assertThat(left.monthlyOrders()).isEqualTo(right.monthlyOrders());
        assertThat(left.activeUsers()).isEqualTo(right.activeUsers());
        assertThat(left.cumulativeRevenue()).isEqualByComparingTo(right.cumulativeRevenue());
        assertThat(left.repeatRate()).isEqualByComparingTo(right.repeatRate());
        assertThat(left.averageFrequency()).isEqualByComparingTo(right.averageFrequency());
        assertThat(left.averageOrderValue()).isEqualByComparingTo(right.averageOrderValue());
    }

    private MapSqlParameterSource params(List<Long> userIds) {
        return new MapSqlParameterSource().addValue("userIds", userIds);
    }

    private BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, long denominator) {
        return denominator == 0 ? BigDecimal.ZERO : numerator.divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private Number number(Map<String, Object> row, String key) {
        return (Number) row.get(key);
    }

    private BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private String durations(List<RunResult> runs) {
        return runs.stream().map(result -> Long.toString(result.elapsedMillis())).collect(java.util.stream.Collectors.joining(", "));
    }

    private long median(List<RunResult> runs) {
        return runs.stream().mapToLong(RunResult::elapsedMillis).sorted().skip(runs.size() / 2).findFirst().orElseThrow();
    }

    private record Aggregate(
            BigDecimal monthlyRevenue,
            long monthlyOrders,
            long activeUsers,
            BigDecimal cumulativeRevenue,
            BigDecimal repeatRate,
            BigDecimal averageFrequency,
            BigDecimal averageOrderValue) {}

    private record RunResult(String name, long elapsedMillis, Aggregate aggregate) {}

    private static final class UserAggregate {
        private int count;
        private BigDecimal revenue = BigDecimal.ZERO;
        private boolean activeInPeriod;
    }

    @FunctionalInterface
    private interface AggregateSupplier {
        Aggregate get() throws Exception;
    }

    private record BenchmarkConfig(String profile, int cohortUsers, int purchasesPerCohortUser, int unrelatedPurchases, int iterations, Path artifactDir) {
        private static BenchmarkConfig fromEnvironment() {
            return new BenchmarkConfig(
                    System.getProperty("cohort.benchmark.profile", "custom"),
                    integer("cohort.benchmark.cohort-users", 200),
                    integer("cohort.benchmark.purchases-per-cohort-user", 10),
                    integer("cohort.benchmark.unrelated-purchases", 50_000),
                    integer("cohort.benchmark.iterations", 5),
                    Path.of(System.getProperty("cohort.benchmark.artifact-dir", "artifacts/benchmark/cohort-sql-offloading")));
        }

        private int unrelatedUsers() {
            return Math.max(1, unrelatedPurchases / 10);
        }

        private int totalPurchases() {
            return cohortUsers * purchasesPerCohortUser + unrelatedPurchases;
        }

        private double cohortPurchaseShare() {
            return (double) (cohortUsers * purchasesPerCohortUser) / totalPurchases() * 100;
        }

        private static int integer(String name, int defaultValue) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        }
    }
}
