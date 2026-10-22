package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.controller.CampaignEntryHistoryController;
import com.axon.core_service.domain.user.CustomOAuth2User;
import com.axon.core_service.domain.user.Role;
import com.axon.core_service.service.CampaignEntryHistoryService;
import com.axon.core_service.support.ApiExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.web.filter.OncePerRequestFilter;

/** HTTP/JPA comparison against the retained 3M SQL Lab fixture; enable with CAMPAIGN_ENTRY_HISTORY_BENCHMARK=true. */
@SpringBootTest(classes = CampaignEntryHistoryHttpBenchmarkIT.FocusedApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("campaign-entry-history-benchmark")
@EnabledIfEnvironmentVariable(named = "CAMPAIGN_ENTRY_HISTORY_BENCHMARK", matches = "true")
class CampaignEntryHistoryHttpBenchmarkIT {
    private static final String HOST = "jdbc:mysql://127.0.0.1:13306/";
    private static final String USER = "root";
    private static final String PASSWORD = "benchmark-local-only";
    private static final String API_SCHEMA = "bench_entry_api";
    private static final String SOURCE_SCHEMA = "bench_entries";
    private static final String SOURCE_TABLE = "campaign_activity_entries_3m";
    private static final String API_TABLE = "campaign_activity_entries";
    private static final String OWNER = "axon-campaign-entry-http-benchmark";
    private static final String VERSION = "1";
    private static final String INDEX = "idx_campaign_entry_user_id";
    private static final String PATH = "/api/v1/me/campaign-entries";
    private static final Long[] SAMPLE_USERS = {1001L, 50001L, 99999L};
    private static final Map<Long, String> A_RESULTS = new LinkedHashMap<>();
    private static boolean fixtureReady;
    private static Path artifactDir;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        prepareOwnedView();
        registry.add("spring.datasource.url", () -> HOST + API_SCHEMA + "?useSSL=false&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "Asia/Seoul");
        registry.add("server.error.include-message", () -> "always");
    }

    @Test
    void measuresAuthenticatedCustomerHistoryOverHttpWithNaturalPlans() throws Exception {
        URI base = URI.create("http://localhost:" + port + PATH);
        assertThat(http.getForEntity(base, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(base + "?page=-1&size=20", 1001L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(base + "?page=0&size=0", 1001L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(base + "?page=0&size=101", 1001L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        artifactDir = Path.of("build/benchmark/campaign-entry-history-http",
                java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(artifactDir);
        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, String> plans = new TreeMap<>();
        Map<String, List<Long>> elapsedByArmAndUser = new TreeMap<>();
        String canonicalSignature;
        long indexBuildMs;
        try {
            plans.put("A1", explainAnalyze());
            measureArm(base, "A1", false, samples, elapsedByArmAndUser);

            long ddlStart = System.nanoTime();
            execute("ALTER TABLE " + SOURCE_SCHEMA + "." + SOURCE_TABLE
                    + " ADD INDEX " + INDEX + " (user_id)");
            indexBuildMs = (System.nanoTime() - ddlStart) / 1_000_000;
            plans.put("B", explainAnalyze());
            measureArm(base, "B", true, samples, elapsedByArmAndUser);

        } finally {
            canonicalSignature = restoreA1AndVerify();
            cleanupOwnedSchema();
        }
        writeArtifact(samples, elapsedByArmAndUser, plans, indexBuildMs, canonicalSignature);
    }

    private void measureArm(URI base, String arm, boolean compareToA, List<Map<String, Object>> samples,
                            Map<String, List<Long>> elapsedByArmAndUser) throws Exception {
        for (Long userId : SAMPLE_USERS) {
            ResponseEntity<String> warmup = get(base.toString() + "?page=0&size=20", userId);
            assertResponse(warmup, userId);
            JsonNode warmJson = objectMapper.readTree(warmup.getBody());
            String body = warmJson.toString();
            if (compareToA) {
                assertThat(body).isEqualTo(A_RESULTS.get(userId));
            } else {
                A_RESULTS.put(userId, body);
            }

            List<Long> elapsed = elapsedByArmAndUser.computeIfAbsent(arm + "/" + userId, ignored -> new ArrayList<>());
            int targetSamples = 2;
            for (int round = 1; round <= targetSamples; round++) {
                long start = System.nanoTime();
                ResponseEntity<String> response = get(base.toString() + "?page=0&size=20", userId);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertResponse(response, userId);
                JsonNode json = objectMapper.readTree(response.getBody());
                assertThat(json.toString()).isEqualTo(body);
                elapsed.add(elapsedMs);
                samples.add(Map.of("arm", arm, "userId", userId, "round", round,
                        "clientWallMs", elapsedMs, "rows", json.path("items").size(), "response", json));
            }
            if (variesByMoreThan25Percent(elapsed)) {
                long start = System.nanoTime();
                ResponseEntity<String> response = get(base.toString() + "?page=0&size=20", userId);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertResponse(response, userId);
                assertThat(objectMapper.readTree(response.getBody()).toString()).isEqualTo(body);
                elapsed.add(elapsedMs);
                samples.add(Map.of("arm", arm, "userId", userId, "round", 3,
                        "clientWallMs", elapsedMs, "rows", 10, "response", objectMapper.readTree(response.getBody())));
            }
        }
    }

    private ResponseEntity<String> get(String url, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Benchmark-UserId", userId.toString());
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void assertResponse(ResponseEntity<String> response, Long userId) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.path("page").asInt()).isZero();
        assertThat(json.path("size").asInt()).isEqualTo(20);
        assertThat(json.path("hasNext").asBoolean()).isFalse();
        assertThat(json.path("items")).hasSize(10);
        assertThat(json.path("items").get(0).has("activityName")).isTrue();
        assertThat(json.path("items").get(0).has("userId")).isFalse();
        assertThat(json.path("items").get(0).has("additionalData")).isFalse();
        List<JsonNode> items = new ArrayList<>();
        json.path("items").forEach(items::add);
        assertThat(items).extracting(n -> n.path("campaignActivityId").asLong())
                .allMatch(id -> id >= 1 && id <= 300);
    }

    private static boolean variesByMoreThan25Percent(List<Long> values) {
        long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        return min > 0 && (max - min) * 4 > min;
    }

    private String explainAnalyze() throws Exception {
        String sql = "EXPLAIN ANALYZE SELECT e.id,a.id,a.name,a.activity_type,e.status,e.requested_at,"
                + "e.processed_at,e.product_id FROM " + API_SCHEMA + "." + API_TABLE + " e JOIN "
                + API_SCHEMA + ".campaign_activities a ON a.id=e.campaign_activity_id "
                + "WHERE e.user_id=1001 ORDER BY e.requested_at DESC,e.id DESC LIMIT 21";
        try (Connection connection = connection("mysql"); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            StringBuilder out = new StringBuilder();
            while (rs.next()) out.append(rs.getString(1)).append('\n');
            return out.toString();
        }
    }

    private void writeArtifact(List<Map<String, Object>> samples, Map<String, List<Long>> elapsed,
                               Map<String, String> plans, long indexBuildMs, String canonicalSignature) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixture", "bench_entries.campaign_activity_entries_3m (3M rows; retained)");
        data.put("canonical_restored", true);
        data.put("canonical_index_signature_after_restore", canonicalSignature);
        data.put("api", "GET /api/v1/me/campaign-entries?page=0&size=20");
        data.put("authentication", "test-only X-Benchmark-UserId filter builds CustomOAuth2User; JWT and production SecurityConfig excluded");
        data.put("measurement", "HTTP client wall milliseconds; no concurrency; includes local request/serialization; no startup time");
        data.put("indexBuildClientWallMs", indexBuildMs);
        data.put("samples", samples);
        data.put("armUserElapsedMs", elapsed);
        data.put("naturalExplainAnalyze", plans);
        data.put("query", "SELECT entry projection JOIN campaign activity scalar metadata WHERE user_id=? ORDER BY requested_at DESC,id DESC LIMIT 21");
        data.put("limitations", List.of("synthetic 3M data; each sampled user has 10 rows", "event name/type join differs from earlier SQL-only benchmark",
                "index DDL and cache/statistics transitions can affect HTTP timing", "no coupon/UserCoupon history or production JWT path measured"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(artifactDir.resolve("raw.json").toFile(), data);
        Files.writeString(artifactDir.resolve("commands.sql"), "-- A1 natural plan\n" + plans.get("A1")
                + "\n-- B adds only user_id index to 3M source; DDL client wall ms=" + indexBuildMs
                + "\n-- B natural plan\n" + plans.get("B") + "\n-- finally restores A1 and verifies index signature\n");
    }

    private static void prepareOwnedView() {
        try (Connection admin = connection("mysql"); Statement statement = admin.createStatement()) {
            verifyLabAndSource(statement);
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + API_SCHEMA + "'")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    statement.execute("CREATE DATABASE " + API_SCHEMA);
                    statement.execute("CREATE TABLE " + API_SCHEMA + ".fixture_meta (id INT PRIMARY KEY, owner VARCHAR(100), version VARCHAR(20), ready TINYINT NOT NULL)");
                    statement.execute("INSERT INTO " + API_SCHEMA + ".fixture_meta VALUES (1,'" + OWNER + "','" + VERSION + "',1)");
                    statement.execute("CREATE TABLE " + API_SCHEMA + ".campaign_activities (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, activity_type VARCHAR(100) NOT NULL)");
                    for (int start = 1; start <= 300; start += 100) {
                        StringBuilder values = new StringBuilder();
                        for (int id = start; id < Math.min(start + 100, 301); id++) {
                            if (!values.isEmpty()) values.append(',');
                            values.append('(').append(id).append(",'Synthetic activity ").append(id)
                                    .append("','FIRST_COME_FIRST_SERVE')");
                        }
                        statement.execute("INSERT INTO " + API_SCHEMA + ".campaign_activities VALUES " + values);
                    }
                    statement.execute("CREATE ALGORITHM=MERGE SQL SECURITY INVOKER VIEW " + API_SCHEMA + "." + API_TABLE + " AS SELECT id,campaign_activity_id,product_id,status,user_id,requested_at,processed_at,additional_data,created_at,updated_at FROM " + SOURCE_SCHEMA + "." + SOURCE_TABLE);
                }
            }
            verifyOwnedSchema(statement);
            fixtureReady = true;
        } catch (Exception ex) {
            throw new IllegalStateException("could not prepare guarded HTTP view fixture; source was not modified", ex);
        }
    }

    private static void verifyOwnedSchema(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT owner,version,ready FROM " + API_SCHEMA + ".fixture_meta WHERE id=1")) {
            if (!rs.next() || !OWNER.equals(rs.getString(1)) || !VERSION.equals(rs.getString(2)) || rs.getInt(3) != 1) {
                throw new IllegalStateException("bench_entry_api owner/version/ready guard mismatch");
            }
        }
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + API_SCHEMA + ".campaign_activities")) {
            rs.next();
            if (rs.getInt(1) != 300) throw new IllegalStateException("activity fixture count mismatch");
        }
        try (ResultSet rs = statement.executeQuery("SELECT table_type FROM information_schema.tables WHERE table_schema='" + API_SCHEMA + "' AND table_name='" + API_TABLE + "'")) {
            if (!rs.next() || !"VIEW".equals(rs.getString(1))) throw new IllegalStateException("entry fixture is not an owned view");
        }
    }

    private static void verifyLabAndSource(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT lab_id FROM benchmark_lab.identity WHERE id=1")) {
            if (!rs.next() || !"axon-local-sql-lab".equals(rs.getString(1))) {
                throw new IllegalStateException("SQLLab identity mismatch");
            }
        }
        try (ResultSet rs = statement.executeQuery("SELECT owner,version,logical_seed,row_count,ready FROM "
                + SOURCE_SCHEMA + ".entry_history_fixture_meta_3m WHERE id=1")) {
            if (!rs.next() || !"axon-campaign-entry-history-3m-benchmark".equals(rs.getString(1))
                    || !"1".equals(rs.getString(2)) || rs.getLong(4) != 3_000_000 || rs.getInt(5) != 1) {
                throw new IllegalStateException("3M source owner/version/ready guard mismatch");
            }
        }
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + SOURCE_SCHEMA + "." + SOURCE_TABLE)) {
            rs.next();
            if (rs.getLong(1) != 3_000_000) throw new IllegalStateException("3M source row count mismatch");
        }
        String signature;
        try (ResultSet rs = statement.executeQuery("SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics WHERE table_schema='"
                + SOURCE_SCHEMA + "' AND table_name='" + SOURCE_TABLE + "' ORDER BY index_name,seq_in_index")) {
            StringBuilder out = new StringBuilder();
            while (rs.next()) out.append(rs.getString(1)).append('\t').append(rs.getInt(2)).append('\t')
                    .append(rs.getString(3)).append('\t').append(rs.getInt(4)).append('\n');
            signature = out.toString();
        }
        String expected = "PRIMARY\t1\tid\t0\nuk_entry_activity_user\t1\tcampaign_activity_id\t0\n"
                + "uk_entry_activity_user\t2\tuser_id\t0\n";
        if (!expected.equals(signature)) throw new IllegalStateException("3M source is not canonical A1: " + signature);
    }

    private static String restoreA1AndVerify() {
        try (Connection connection = connection("mysql"); Statement statement = connection.createStatement()) {
            if (indexExists(statement)) statement.execute("ALTER TABLE " + SOURCE_SCHEMA + "." + SOURCE_TABLE + " DROP INDEX " + INDEX);
            String signature;
            try (ResultSet rs = statement.executeQuery("SELECT index_name,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema='" + SOURCE_SCHEMA + "' AND table_name='" + SOURCE_TABLE + "' ORDER BY index_name,seq_in_index")) {
                StringBuilder out = new StringBuilder();
                while (rs.next()) out.append(rs.getString(1)).append('\t').append(rs.getInt(2)).append('\t').append(rs.getString(3)).append('\n');
                signature = out.toString();
            }
            if (!signature.contains("PRIMARY\t1\tid\n") || !signature.contains("uk_entry_activity_user\t2\tuser_id\n")
                    || signature.contains(INDEX)) throw new IllegalStateException("3M source A1 index signature not restored: " + signature);
            return signature;
        } catch (Exception ex) {
            throw new IllegalStateException("failed restoring/verifying source A1", ex);
        }
    }

    private static boolean indexExists(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='" + SOURCE_SCHEMA + "' AND table_name='" + SOURCE_TABLE + "' AND index_name='" + INDEX + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static void cleanupOwnedSchema() throws Exception {
        if (!fixtureReady) return;
        try (Connection connection = connection("mysql"); Statement statement = connection.createStatement()) {
            verifyOwnedSchema(statement);
            statement.execute("DROP DATABASE " + API_SCHEMA);
            fixtureReady = false;
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connection("mysql"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connection(String schema) throws Exception {
        return DriverManager.getConnection(HOST + schema + "?useSSL=false&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true", USER, PASSWORD);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (fixtureReady) cleanupOwnedSchema();
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("campaign-entry-history-benchmark")
    @EnableAutoConfiguration(excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EntityScan("com.axon.core_service.domain")
    @EnableJpaRepositories(basePackageClasses = com.axon.core_service.benchmark.repository.FocusedCampaignActivityEntryRepository.class)
    @Import({CampaignEntryHistoryController.class, CampaignEntryHistoryService.class, ApiExceptionHandler.class, TestAuthConfiguration.class})
    static class FocusedApplication {}

    @EnableWebSecurity
    static class TestAuthConfiguration {
        @Bean
        SecurityFilterChain benchmarkSecurity(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, PATH).authenticated().anyRequest().denyAll())
                    .addFilterBefore(new BenchmarkPrincipalFilter(), AnonymousAuthenticationFilter.class).build();
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class BenchmarkPrincipalFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String value = request.getHeader("X-Benchmark-UserId");
            if (value != null) {
                Long userId = Long.valueOf(value);
                CustomOAuth2User principal = new CustomOAuth2User(List.of(() -> Role.USER.getKey()),
                        Map.of("id", userId.toString()), "id", userId, "Synthetic customer");
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            }
            chain.doFilter(request, response);
        }
    }
}
