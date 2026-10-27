package com.axon.entry_service.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.entry_service.config.auth.JwtTokenProvider;
import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.CampaignActivityStatus;
import com.axon.entry_service.payment.CheckoutTokenService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.payment.ReservationTokenPayload;
import com.axon.messaging.topic.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.ServerSocket;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;
import java.util.function.Function;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Opt-in real HTTP/API comparison on disposable local Redis and Kafka containers. */
@Tag("redis-experiment")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=local-http-benchmark-jwt-secret-not-a-credential-32chars",
        "payment.token.secret=local-http-benchmark-payment-secret",
        "spring.data.redis.password=",
        "spring.threads.virtual.enabled=true",
        "axon.backend-event.executor.core-size=2",
        "axon.backend-event.executor.max-size=2",
        "axon.backend-event.executor.queue-capacity=1000"
})
@Import(EntryReservationHttpComparisonIT.HarnessConfig.class)
class EntryReservationHttpComparisonIT {
    private static final int REQUESTS = 300;
    private static final int LIMIT = 100;
    private static final int WORKERS = 64;
    private static final int WARMUPS = 3;
    private static final int REPETITIONS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static final int KAFKA_HOST_PORT = reservePort();
    static final GenericContainer<?> KAFKA = new GenericContainer<>(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withExposedPorts(9092)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(KAFKA_HOST_PORT), new ExposedPort(9092))))
            .withEnv("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk")
            .withEnv("KAFKA_NODE_ID", "1")
            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093")
            .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + KAFKA_HOST_PORT)
            .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
            .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.lettuce.pool.max-active", () -> 8);
        registry.add("spring.data.redis.lettuce.pool.max-idle", () -> 8);
        registry.add("spring.data.redis.lettuce.pool.min-idle", () -> 0);
        registry.add("spring.data.redis.lettuce.pool.max-wait", () -> "1000ms");
        registry.add("spring.kafka.bootstrap-servers", EntryReservationHttpComparisonIT::kafkaBootstrapServers);
    }

    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired StringRedisTemplate redis;
    @Autowired CheckoutTokenService tokenService;
    @Autowired ObjectMapper objectMapper;
    @Autowired SwitchableReservationTemplate reservationTemplate;
    @Autowired ServerRunMeasurements serverMeasurements;
    @LocalServerPort int port;

    @BeforeAll
    static void createBehaviorTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
            try {
                admin.createTopics(List.of(new NewTopic(KafkaTopics.BEHAVIOR_EVENT, 1, (short) 1)))
                        .all().get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException error) {
                if (!(error.getCause() instanceof TopicExistsException)) throw error;
            }
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void stopKafkaContainers() {
        KAFKA.stop();
    }

    private static String kafkaBootstrapServers() { return KAFKA.getHost() + ":" + KAFKA.getMappedPort(9092); }
    private static int reservePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException error) { throw new IllegalStateException("Unable to reserve local Kafka port", error); }
    }

    @Test
    void compareProductionHttpEntryPathWithEquivalentSeparateRedisCalls() throws Exception {
        String output = System.getenv("HTTP_BENCHMARK_DIR");
        assertThat(output).as("HTTP_BENCHMARK_DIR output directory").isNotBlank();
        String baseline = System.getenv().getOrDefault("HTTP_BENCHMARK_BASELINE", "SEPARATE_CALLS");
        assertThat(Set.of("SEPARATE_CALLS", "WATCH_MULTI")).contains(baseline);
        Path directory = Path.of(output);
        Files.createDirectories(directory);
        Path csv = directory.resolve("per-run.csv");
        Files.writeString(csv, "phase,repetition,algorithm,wall_ms,throughput_rps,server_mean_ms,server_p50_ms,server_p95_ms,server_200_p95_ms,server_410_p95_ms,client_mean_ms,client_p50_ms,client_p95_ms,server_samples,client_200,client_410,client_other,server_200,server_410,server_other,virtual_server_samples,kafka_approved,kafka_unique_users,watch_abort_retries,redis_counter,redis_participants\n");

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        for (int i = 1; i <= WARMUPS; i++) {
            executeRun(client, baseline, "warmup", i, csv);
            executeRun(client, "LUA", "warmup", i, csv);
        }
        List<Run> baselineRuns = new ArrayList<>();
        List<Run> lua = new ArrayList<>();
        for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
            String first = repetition % 2 == 1 ? baseline : "LUA";
            Run a = executeRun(client, first, "measured", repetition, csv);
            Run b = executeRun(client, first.equals("LUA") ? baseline : "LUA", "measured", repetition, csv);
            if (first.equals("LUA")) { lua.add(a); baselineRuns.add(b); }
            else { baselineRuns.add(a); lua.add(b); }
        }
        assertThat(baselineRuns).allSatisfy(EntryReservationHttpComparisonIT::assertCorrect);
        assertThat(lua).allSatisfy(EntryReservationHttpComparisonIT::assertCorrect);
        Files.writeString(directory.resolve("result.md"), report(baseline, baselineRuns, lua));
    }

    private Run executeRun(HttpClient client, String algorithm, String phase, int repetition, Path csv) throws Exception {
        long runNumber = RUN_SEQUENCE.incrementAndGet();
        long activityId = 7_000_000L + runNumber;
        long productId = 9_000_001L;
        String runId = "r" + runNumber + "-" + UUID.randomUUID().toString().substring(0, 8);
        String usersKey = "campaign:" + activityId + ":users";
        String counterKey = "campaign:" + activityId + ":counter";
        String metaKey = "campaign:" + activityId + ":meta";
        CampaignActivityMeta meta = new CampaignActivityMeta(activityId, 1L, LIMIT, CampaignActivityStatus.ACTIVE,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(20), List.of(), false, false,
                productId, null, CampaignActivityType.FIRST_COME_FIRST_SERVE, null);
        long firstUserId = 4_000_000_000L + runNumber * REQUESTS;
        List<RequestSpec> requests = IntStream.range(0, REQUESTS).mapToObj(i -> {
            long userId = firstUserId + i;
            String jwt = jwtTokenProvider.generateAccessToken(userId);
            String body = "{\"campaignActivityId\":" + activityId + ",\"productId\":" + productId + ",\"quantity\":1,\"campaignActivityType\":\"FIRST_COME_FIRST_SERVE\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/entries"))
                    .timeout(REQUEST_TIMEOUT).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                    .header("Content-Type", "application/json").header("X-Benchmark-Run", runId)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            return new RequestSpec(userId, request);
        }).toList();

        redis.delete(List.of(metaKey, usersKey, counterKey));
        List<String> staleTokenKeys = requests.stream().map(spec -> "RESERVATION_TOKEN:" +
                tokenService.generateDeterministicToken(spec.userId(), activityId)).toList();
        redis.delete(staleTokenKeys);
        redis.opsForValue().set(metaKey, objectMapper.writeValueAsString(meta), Duration.ofHours(1));
        assertThat(redis.opsForValue().get(metaKey)).isNotNull();
        serverMeasurements.clear(runId, REQUESTS);
        reservationTemplate.setAlgorithm(algorithm);

        Set<Long> approvedUserIds = ConcurrentHashMap.newKeySet();
        long[] clientNanos = new long[REQUESTS];
        AtomicReferenceArray<HttpResponse<String>> responses = new AtomicReferenceArray<>(REQUESTS);
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        long startNanos;
        long wallNanos;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProperties(runId))) {
            consumer.subscribe(List.of(KafkaTopics.BEHAVIOR_EVENT));
            long assignmentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (consumer.assignment().isEmpty() && System.nanoTime() < assignmentDeadline) {
                consumer.poll(Duration.ofMillis(200));
            }
            assertThat(consumer.assignment()).isNotEmpty();
            List<Future<?>> futures = new ArrayList<>(WORKERS);
            for (int worker = 0; worker < WORKERS; worker++) {
                final int workerId = worker;
                Callable<Void> task = () -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("worker start timeout");
                    for (int index = workerId; index < REQUESTS; index += WORKERS) {
                        RequestSpec spec = requests.get(index);
                        long began = System.nanoTime();
                        HttpResponse<String> response = client.send(spec.request(), HttpResponse.BodyHandlers.ofString());
                        clientNanos[index] = System.nanoTime() - began;
                        responses.set(index, response);
                    }
                    return null;
                };
                futures.add(executor.submit(task));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("workers not ready");
            startNanos = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) future.get(REQUEST_TIMEOUT.toSeconds() * 4, TimeUnit.SECONDS);
            wallNanos = System.nanoTime() - startNanos;
            assertThat(serverMeasurements.await(runId, Duration.ofSeconds(10))).isTrue();
            List<Integer> clientStatuses = new ArrayList<>(REQUESTS);
            Map<Long, String> reservationTokens = new HashMap<>();
            for (int index = 0; index < REQUESTS; index++) {
                HttpResponse<String> response = responses.get(index);
                assertThat(response).as("HTTP response for request " + index).isNotNull();
                clientStatuses.add(response.statusCode());
                if (response.statusCode() == 200) {
                    JsonNode body = objectMapper.readTree(response.body());
                    String token = body.path("reservationToken").asText("");
                    assertThat(token).as("successful response token").isNotBlank();
                    approvedUserIds.add(requests.get(index).userId());
                    reservationTokens.put(requests.get(index).userId(), token);
                } else {
                    assertThat(response.statusCode()).isEqualTo(410);
                }
            }
            for (Map.Entry<Long, String> entry : reservationTokens.entrySet()) {
                ReservationTokenPayload payload = tokenService.getPayloadFromToken(entry.getValue()).orElse(null);
                assertThat(payload).as("real Redis reservation token payload").isNotNull();
                assertThat(payload.getUserId()).isEqualTo(entry.getKey());
                assertThat(payload.getCampaignActivityId()).isEqualTo(activityId);
                assertThat(payload.getProductId()).isEqualTo(productId);
                assertThat(payload.getQuantity()).isEqualTo(1);
            }
            long eventsDeadline = System.nanoTime() + EVENT_TIMEOUT.toNanos();
            List<Long> observedEventUserIds = new ArrayList<>();
            while (observedEventUserIds.size() < LIMIT && System.nanoTime() < eventsDeadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    JsonNode event = objectMapper.readTree(record.value());
                    JsonNode properties = event.path("properties");
                    if (properties.path("activityId").asLong(-1) == activityId) {
                        observedEventUserIds.add(event.path("userId").asLong());
                    }
                }
            }
            assertThat(observedEventUserIds).hasSize(LIMIT).doesNotHaveDuplicates();
            assertThat(new HashSet<>(observedEventUserIds)).containsExactlyInAnyOrderElementsOf(approvedUserIds);
            long counter = parseLong(redis.opsForValue().get(counterKey));
            long participants = redis.opsForSet().size(usersKey);
            ServerSamples server = serverMeasurements.samples(runId);
            Run result = Run.of(algorithm, phase, repetition, clientStatuses,
                    Arrays.stream(clientNanos).boxed().toList(), server, wallNanos,
                    observedEventUserIds.size(), new HashSet<>(observedEventUserIds).size(),
                    reservationTemplate.watchAbortRetries(), counter, participants);
            Files.writeString(csv, result.csv(), java.nio.file.StandardOpenOption.APPEND);
            assertCorrect(result);
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Properties consumerProperties(String runId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "http-bench-" + runId);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "http-bench-consumer-" + runId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return props;
    }

    private static long parseLong(String value) { return value == null ? 0 : Long.parseLong(value); }

    private static void assertCorrect(Run run) {
        assertThat(run.clientStatuses().size()).isEqualTo(REQUESTS);
        assertThat(run.serverSamples()).isEqualTo(REQUESTS);
        assertThat(run.serverVirtual()).as("server filter observed virtual threads").isEqualTo(REQUESTS);
        assertThat(run.client200()).isEqualTo(LIMIT);
        assertThat(run.client410()).isEqualTo(REQUESTS - LIMIT);
        assertThat(run.clientOther()).isZero();
        assertThat(run.server200()).isEqualTo(run.client200());
        assertThat(run.server410()).isEqualTo(run.client410());
        assertThat(run.serverOther()).isZero();
        assertThat(run.kafkaApproved()).isEqualTo(LIMIT);
        assertThat(run.kafkaUniqueUsers()).isEqualTo(LIMIT);
        assertThat(run.counter()).isEqualTo(LIMIT);
        assertThat(run.participants()).isEqualTo(LIMIT);
    }

    private static String report(String baseline, List<Run> baselineRuns, List<Run> lua) {
        double baselineServer = median(baselineRuns.stream().mapToDouble(Run::serverP95).toArray());
        double luaServer = median(lua.stream().mapToDouble(Run::serverP95).toArray());
        double baselineClient = median(baselineRuns.stream().mapToDouble(Run::clientP95).toArray());
        double luaClient = median(lua.stream().mapToDouble(Run::clientP95).toArray());
        double baselineSuccessP95 = median(baselineRuns.stream().mapToDouble(Run::server200P95).toArray());
        double luaSuccessP95 = median(lua.stream().mapToDouble(Run::server200P95).toArray());
        double baselineSoldOutP95 = median(baselineRuns.stream().mapToDouble(Run::server410P95).toArray());
        double luaSoldOutP95 = median(lua.stream().mapToDouble(Run::server410P95).toArray());
        double baselineThroughput = median(baselineRuns.stream().mapToDouble(Run::throughput).toArray());
        double luaThroughput = median(lua.stream().mapToDouble(Run::throughput).toArray());
        double retriesMedian = median(baselineRuns.stream().mapToDouble(Run::watchAbortRetries).toArray());
        return "# Entry HTTP " + baseline + " vs production Lua\n\n"
                + "Run conditions: 300 unique users, limit 100, 64 HTTP workers, 3 warmups per variant, 3 alternating measured pairs; same Spring Boot RANDOM_PORT process and disposable Redis 7/Kafka containers. Active FCFS metadata was preloaded identically with no filters. All normal JWT/security, application, Redis token, async event and Kafka publication paths remained enabled.\n\n"
                + (baseline.equals("WATCH_MULTI")
                    ? "Baseline uses the archived WATCH + MULTI/EXEC flow: watch participant/counter keys, check duplicate and current count, unwatch on duplicate/sold-out, otherwise queue SADD+INCR and retry aborted EXEC with 1-3 ms jitter (maximum 1,000 attempts). Retry count is per measured run.\n\n"
                    : "Baseline is normalized equivalent separate calls `SADD -> INCR -> over-limit SREM+DECR`, not literal historical commit 8a100d7 (that source omitted DECR).\n\n")
                + "Lua path delegates to the exact production reservation script.\n\n"
                + "Primary API server-filter median run p95: " + baseline + " " + fmt(baselineServer) + " ms (range " + range(baselineRuns, true) + "), Lua " + fmt(luaServer) + " ms (range " + range(lua, true) + "). Loopback HTTP-client median run p95: " + fmt(baselineClient) + " ms vs " + fmt(luaClient) + " ms. Median HTTP-interval throughput: " + fmt(baselineThroughput) + " vs " + fmt(luaThroughput) + " requests/s.\n\n"
                + "Server-filter status-split median p95: HTTP 200 success " + fmt(baselineSuccessP95) + " vs " + fmt(luaSuccessP95) + " ms; HTTP 410 sold-out " + fmt(baselineSoldOutP95) + " vs " + fmt(luaSoldOutP95) + " ms. Median WATCH abort retries per run: " + fmt(retriesMedian) + ".\n\n"
                + "Every run recorded exactly 300 server samples on virtual threads; server/client status counts matched (100 x HTTP 200, 200 x HTTP 410), exactly 100 Kafka event records with 100 unique approved users were observed before switching variants, and Redis ended at counter/set size 100.\n\n"
                + "Pool settings match app defaults (8 max active/idle, 0 min idle, 1 s wait); Lettuce shared-native-connection remains true. The WATCH SessionCallback uses the transaction's dedicated Spring Data connection, while Lua follows the ordinary shared-native path; pool 8 is not an artificial cap applied equally to both. Local CPU was unrestricted and not controlled. The servlet-filter metric covers the request through the complete filter chain/controller/serialization, but excludes socket/network and pre-servlet queueing; client latency includes loopback HTTP transport. This is a local 300-request test, not a 3k-VU/1k-RPS or VM performance claim.\n\n"
                + "Test-only instrumentation/interception only; no production/config/build edits. JUnit result is in the test report; per-run values are in `per-run.csv`.\n";
    }

    private static String range(List<Run> runs, boolean server) {
        double min = runs.stream().mapToDouble(r -> server ? r.serverP95() : r.clientP95()).min().orElseThrow();
        double max = runs.stream().mapToDouble(r -> server ? r.serverP95() : r.clientP95()).max().orElseThrow();
        return fmt(min) + "-" + fmt(max);
    }
    private static String fmt(double value) { return String.format(java.util.Locale.ROOT, "%.3f", value); }
    private static double median(double[] values) { Arrays.sort(values); return values[values.length / 2]; }
    private static final java.util.concurrent.atomic.AtomicLong RUN_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    private record RequestSpec(long userId, HttpRequest request) {}
    private record ServerSample(long nanos, int status, boolean virtual) {}
    private record ServerSamples(List<ServerSample> values) {}
    private record Run(String algorithm, String phase, int repetition, double throughput, double serverMean,
                       double serverP50, double serverP95, double server200P95, double server410P95,
                       double clientMean, double clientP50, double clientP95,
                       List<Integer> clientStatuses, long serverSamples, long client200, long client410,
                       long clientOther, long server200, long server410, long serverOther, long serverVirtual,
                       long kafkaApproved, long kafkaUniqueUsers, long watchAbortRetries,
                       long counter, long participants) {
        static Run of(String algorithm, String phase, int repetition, List<Integer> clientStatuses,
                      List<Long> clientNanos, ServerSamples server, long wallNanos, long kafkaApproved,
                      long kafkaUniqueUsers, long watchAbortRetries, long counter, long participants) {
            List<Long> serverNs = server.values().stream().map(ServerSample::nanos).sorted().toList();
            List<Long> successNs = server.values().stream().filter(s -> s.status() == 200).map(ServerSample::nanos).sorted().toList();
            List<Long> soldOutNs = server.values().stream().filter(s -> s.status() == 410).map(ServerSample::nanos).sorted().toList();
            List<Long> clientNs = clientNanos.stream().sorted().toList();
            long server200 = server.values().stream().filter(s -> s.status() == 200).count();
            long server410 = server.values().stream().filter(s -> s.status() == 410).count();
            long client200 = clientStatuses.stream().filter(s -> s == 200).count();
            long client410 = clientStatuses.stream().filter(s -> s == 410).count();
            long serverVirtual = server.values().stream().filter(ServerSample::virtual).count();
            return new Run(algorithm, phase, repetition, REQUESTS / (wallNanos / 1e9), mean(serverNs), percentile(serverNs, .50), percentile(serverNs, .95), percentile(successNs, .95), percentile(soldOutNs, .95),
                    mean(clientNs), percentile(clientNs, .50), percentile(clientNs, .95), List.copyOf(clientStatuses), server.values().size(),
                    client200, client410, clientStatuses.size() - client200 - client410, server200, server410,
                    server.values().size() - server200 - server410, serverVirtual, kafkaApproved, kafkaUniqueUsers,
                    watchAbortRetries, counter, participants);
        }
        String csv() {
            return phase+","+repetition+","+algorithm+","+fmt(300/throughput*1000)+","+throughput+","+serverMean+","+serverP50+","+serverP95+","+server200P95+","+server410P95+","+clientMean+","+clientP50+","+clientP95+","+serverSamples+","+client200+","+client410+","+clientOther+","+server200+","+server410+","+serverOther+","+serverVirtual+","+kafkaApproved+","+kafkaUniqueUsers+","+watchAbortRetries+","+counter+","+participants+"\n";
        }
    }
    private static double mean(List<Long> values) { return values.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6; }
    private static double percentile(List<Long> values, double percentile) { return values.get((int)Math.ceil(values.size()*percentile)-1)/1e6; }

    @TestConfiguration
    static class HarnessConfig {
        @Bean
        SwitchableReservationTemplate switchableReservationTemplate(RedisConnectionFactory factory,
                ApplicationEventPublisher publisher) {
            return new SwitchableReservationTemplate(factory, publisher);
        }
        @Bean
        @org.springframework.context.annotation.Primary
        EntryReservationService primaryReservationService(SwitchableReservationTemplate template,
                ApplicationEventPublisher publisher) {
            return new EntryReservationService(template, publisher);
        }
        @Bean ServerRunMeasurements serverRunMeasurements() { return new ServerRunMeasurements(); }
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> benchmarkTimingFilter(ServerRunMeasurements measurements) {
            FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
            bean.setFilter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    long began = System.nanoTime();
                    try { chain.doFilter(request, response); }
                    finally {
                        String id = request.getHeader("X-Benchmark-Run");
                        if (id != null) measurements.add(id, new ServerSample(System.nanoTime()-began,
                                response.getStatus(), Thread.currentThread().isVirtual()));
                    }
                }
            });
            bean.addUrlPatterns("/api/v1/entries");
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
            return bean;
        }
    }

    static class ServerRunMeasurements {
        private final Map<String, List<ServerSample>> byRun = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> expected = new ConcurrentHashMap<>();
        void clear(String run, int expectedCount) {
            byRun.remove(run);
            expected.put(run, new CountDownLatch(expectedCount));
        }
        void add(String run, ServerSample sample) {
            byRun.computeIfAbsent(run, ignored -> new CopyOnWriteArrayList<>()).add(sample);
            CountDownLatch latch = expected.get(run);
            if (latch != null) latch.countDown();
        }
        boolean await(String run, Duration timeout) throws InterruptedException {
            CountDownLatch latch = expected.get(run);
            return latch != null && latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        ServerSamples samples(String run) { return new ServerSamples(List.copyOf(byRun.getOrDefault(run, List.of()))); }
    }

    static class SwitchableReservationTemplate extends StringRedisTemplate {
        private static final long WATCH_RETRY = -3L;
        private static final int MAX_WATCH_ATTEMPTS = 1_000;
        private final AtomicReference<String> algorithm = new AtomicReference<>("LUA");
        private final AtomicLong watchAbortRetries = new AtomicLong();
        private final String reservationScriptSha;
        SwitchableReservationTemplate(RedisConnectionFactory factory, ApplicationEventPublisher publisher) {
            super(factory);
            EntryReservationService service = new EntryReservationService(this, publisher);
            @SuppressWarnings("unchecked")
            RedisScript<Long> script = (RedisScript<Long>) ReflectionTestUtils.getField(service, "reservationScript");
            reservationScriptSha = script.getSha1();
        }
        void setAlgorithm(String next) {
            watchAbortRetries.set(0);
            algorithm.set(next);
        }
        long watchAbortRetries() { return watchAbortRetries.get(); }
        @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (!reservationScriptSha.equals(script.getSha1()) || "LUA".equals(algorithm.get())) {
                return super.execute(script, keys, args);
            }
            String user = String.valueOf(args[0]);
            long limit = Long.parseLong(String.valueOf(args[1]));
            if ("WATCH_MULTI".equals(algorithm.get())) {
                @SuppressWarnings("unchecked") T watched = (T) Long.valueOf(watchMultiReserve(keys, user, limit));
                return watched;
            }
            Long added = opsForSet().add(keys.get(0), user);
            Long result;
            if (added == null) result = null;
            else if (added == 0) result = -1L;
            else {
                Long count = opsForValue().increment(keys.get(1));
                if (count == null) result = null;
                else if (count > limit) {
                    opsForSet().remove(keys.get(0), user);
                    opsForValue().decrement(keys.get(1));
                    result = -2L;
                } else result = count;
            }
            @SuppressWarnings("unchecked") T cast = (T) result;
            return cast;
        }

        private long watchMultiReserve(List<String> keys, String user, long limit) {
            int attempts = 0;
            while (true) {
                Long result = execute(session(operations -> {
                    operations.watch(keys);
                    if (Boolean.TRUE.equals(operations.opsForSet().isMember(keys.get(0), user))) {
                        operations.unwatch();
                        return -1L;
                    }
                    String rawCount = operations.opsForValue().get(keys.get(1));
                    long current = rawCount == null ? 0 : Long.parseLong(rawCount);
                    if (limit > 0 && current >= limit) {
                        operations.unwatch();
                        return -2L;
                    }
                    operations.multi();
                    operations.opsForSet().add(keys.get(0), user);
                    operations.opsForValue().increment(keys.get(1));
                    List<Object> executed = operations.exec();
                    return executed == null || executed.isEmpty() ? WATCH_RETRY : current + 1;
                }));
                if (result != WATCH_RETRY) return result == null ? 0 : result;
                watchAbortRetries.incrementAndGet();
                if (++attempts > MAX_WATCH_ATTEMPTS) {
                    throw new IllegalStateException("WATCH retry limit exceeded");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("WATCH retry interrupted");
                }
                LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(1_000_000L, 3_000_001L));
            }
        }
    }

    private static <T> SessionCallback<T> session(Function<RedisOperations<String, String>, T> action) {
        return new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> T execute(RedisOperations<K, V> operations) {
                return action.apply((RedisOperations<String, String>) operations);
            }
        };
    }
}
