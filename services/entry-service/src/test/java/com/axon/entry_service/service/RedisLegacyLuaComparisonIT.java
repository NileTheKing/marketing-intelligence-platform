package com.axon.entry_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import io.lettuce.core.api.StatefulConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Opt-in, local-only Redis service-operation benchmark. */
@Tag("redis-experiment")
@Testcontainers
class RedisLegacyLuaComparisonIT {
    private static final int REQUESTS = 300;
    private static final int LIMIT = 100;
    private static final int WORKERS = 64;
    private static final int WARMUPS = 3;
    private static final int REPETITIONS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final String USERS = "redis-bench:legacy-lua:users";
    private static final String COUNTER = "redis-bench:legacy-lua:counter";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Test
    void compareEquivalentMultiCommandBaselineWithProductionLua() throws Exception {
        String output = System.getenv("REDIS_BENCHMARK_DIR");
        assertThat(output).as("redis.benchmark.dir output directory").isNotBlank();
        Path csv = Path.of(output).resolve("per-run.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, "phase,repetition,algorithm,elapsed_ms,throughput_rps,avg_ms,p50_ms,p95_ms,success,sold_out,duplicate,errors,counter,participants\n");

        GenericObjectPoolConfig<StatefulConnection<?, ?>> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(8);
        pool.setMaxIdle(8);
        pool.setMinIdle(0);
        pool.setMaxWait(Duration.ofSeconds(1));
        LettucePoolingClientConfiguration client = LettucePoolingClientConfiguration.builder()
                .poolConfig(pool).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)), client);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate();
        redis.setConnectionFactory(factory);
        redis.afterPropertiesSet();
        EntryReservationService service = new EntryReservationService(redis, mock(ApplicationEventPublisher.class));
        @SuppressWarnings("unchecked")
        RedisScript<Long> lua = (RedisScript<Long>) ReflectionTestUtils.getField(service, "reservationScript");

        try {
            for (int i = 0; i < WARMUPS; i++) {
                run(redis, lua, "SEPARATE_CALLS", "warmup", i + 1, csv);
                run(redis, lua, "LUA", "warmup", i + 1, csv);
            }
            List<Run> multi = new ArrayList<>();
            List<Run> luaRuns = new ArrayList<>();
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                String first = repetition % 2 == 1 ? "SEPARATE_CALLS" : "LUA";
                Run a = run(redis, lua, first, "measured", repetition, csv);
                Run b = run(redis, lua, first.equals("SEPARATE_CALLS") ? "LUA" : "SEPARATE_CALLS", "measured", repetition, csv);
                if (first.equals("SEPARATE_CALLS")) { multi.add(a); luaRuns.add(b); }
                else { luaRuns.add(a); multi.add(b); }
            }
            assertThat(multi).allSatisfy(this::assertCorrect);
            assertThat(luaRuns).allSatisfy(this::assertCorrect);
        } finally {
            try (RedisConnection connection = factory.getConnection()) {
                connection.serverCommands().flushDb();
            }
            factory.destroy();
        }
    }

    private Run run(StringRedisTemplate redis, RedisScript<Long> lua, String algorithm,
                    String phase, int repetition, Path csv) throws Exception {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        List<long[]> samples = new ArrayList<>(REQUESTS);
        List<Future<List<Sample>>> futures = new ArrayList<>(WORKERS);
        long wallStart = 0;
        long elapsed = 0;
        long counter = -1;
        long participants = -1;
        try {
            for (int worker = 0; worker < WORKERS; worker++) {
                final int workerId = worker;
                Callable<List<Sample>> task = () -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    List<Sample> local = new ArrayList<>();
                    for (int index = workerId; index < REQUESTS; index += WORKERS) {
                        long began = System.nanoTime();
                        String user = Integer.toString(index + 1);
                        String outcome;
                        try {
                            if (algorithm.equals("LUA")) {
                                Long result = redis.execute(lua, List.of(USERS, COUNTER), user, Integer.toString(LIMIT));
                                outcome = result == null ? "errors" : result > 0 ? "success" : result == -2 ? "sold_out" : result == -1 ? "duplicate" : "errors";
                            } else {
                                Long added = redis.opsForSet().add(USERS, user);
                                if (added == null) outcome = "errors";
                                else if (added == 0) outcome = "duplicate";
                                else {
                                    Long count = redis.opsForValue().increment(COUNTER);
                                    if (count == null) outcome = "errors";
                                    else if (count > LIMIT) {
                                        redis.opsForSet().remove(USERS, user);
                                        redis.opsForValue().decrement(COUNTER);
                                        outcome = "sold_out";
                                    } else outcome = "success";
                                }
                            }
                        } catch (RuntimeException error) { outcome = "errors"; }
                        local.add(new Sample(System.nanoTime() - began, outcome));
                    }
                    return local;
                };
                futures.add(executor.submit(task));
            }
            if (!ready.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) throw new IllegalStateException("workers not ready");
            wallStart = System.nanoTime();
            start.countDown();
            for (Future<List<Sample>> future : futures) samples.addAll(future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).stream().map(s -> new long[]{s.nanos(), s.code()}).toList());
            elapsed = System.nanoTime() - wallStart;
            counter = parseLong(redis.opsForValue().get(COUNTER));
            participants = redis.opsForSet().size(USERS);
            Run result = Run.of(algorithm, phase, repetition, samples, elapsed, counter, participants);
            Files.writeString(csv, result.csv(), java.nio.file.StandardOpenOption.APPEND);
            return result;
        } catch (Exception failure) {
            if (wallStart != 0 && elapsed == 0) elapsed = System.nanoTime() - wallStart;
            Files.writeString(csv, phase + "," + repetition + "," + algorithm + "," + elapsed / 1e6 + ",,,,,,,,," + counter + "," + participants + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            throw failure;
        } finally { executor.shutdownNow(); }
    }

    private void assertCorrect(Run run) {
        assertThat(run.success()).isEqualTo(LIMIT);
        assertThat(run.soldOut()).isEqualTo(REQUESTS - LIMIT);
        assertThat(run.duplicate()).isZero();
        assertThat(run.errors()).isZero();
        assertThat(run.counter()).isEqualTo(LIMIT);
        assertThat(run.participants()).isEqualTo(LIMIT);
    }

    private static long parseLong(String value) { return value == null ? 0 : Long.parseLong(value); }

    private record Sample(long nanos, String outcome) {
        long code() { return switch (outcome) { case "success" -> 1; case "sold_out" -> 2; case "duplicate" -> 3; default -> 4; }; }
    }
    private record Run(String algorithm, String phase, int repetition, long elapsedNanos, double throughput,
                       double average, double p50, double p95, long success, long soldOut, long duplicate,
                       long errors, long counter, long participants) {
        static Run of(String algorithm, String phase, int repetition, List<long[]> values, long elapsed, long counter, long participants) {
            List<Long> sorted = values.stream().map(v -> v[0]).sorted().toList();
            long sum = sorted.stream().mapToLong(Long::longValue).sum();
            long success = values.stream().filter(v -> v[1] == 1).count();
            long sold = values.stream().filter(v -> v[1] == 2).count();
            long dup = values.stream().filter(v -> v[1] == 3).count();
            long errors = values.stream().filter(v -> v[1] == 4).count();
            return new Run(algorithm, phase, repetition, elapsed, REQUESTS / (elapsed / 1e9), sum / (double) values.size() / 1e6,
                    sorted.get((int)Math.ceil(sorted.size()*.50)-1)/1e6,
                    sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1e6, success, sold, dup, errors, counter, participants);
        }
        String csv() { return phase+","+repetition+","+algorithm+","+elapsedNanos/1e6+","+throughput+","+average+","+p50+","+p95+","+success+","+soldOut+","+duplicate+","+errors+","+counter+","+participants+"\n"; }
    }
}
