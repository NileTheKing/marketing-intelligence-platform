package com.axon.entry_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Experimental Redis comparison. Run explicitly with -PincludeRedisExperiments=true. */
@Tag("redis-experiment")
@Testcontainers(disabledWithoutDocker = true)
class RedisReservationComparisonIT {

    private static final int LIMIT = 800;
    private static final int REQUESTS = 1_000;
    private static final int WORKERS = 64;
    private static final int INTERRUPTED = 50;
    private static final String USERS = "campaign:901:users";
    private static final String COUNTER = "campaign:901:counter";
    private static final String LUA = """
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 0 then return -1 end
            local count = redis.call('INCR', KEYS[2])
            if tonumber(ARGV[2]) > 0 and count > tonumber(ARGV[2]) then
              redis.call('SREM', KEYS[1], ARGV[1])
              redis.call('DECR', KEYS[2])
              return -2
            end
            return count
            """;
    private static final RedisScript<Long> RESERVATION_SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @BeforeEach
    void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate();
        redis.setConnectionFactory(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void disconnect() {
        if (connectionFactory != null) {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.serverCommands().flushAll();
            }
            connectionFactory.destroy();
        }
    }

    /**
     * Reproduces the INCR-first interruption point described in the request.
     * It intentionally asserts the desired invariant and is expected to fail,
     * showing the observed ghost count for this injected interruption.
     */
    @Test
    void legacyInterruptionAfterIncrementExposesGhostReservations() throws Exception {
        RunResult result = runConcurrent(true, Algorithm.INCR_FIRST_REPRO);
        print("legacy-fault", result);

        assertThat(result.counter - result.participants).as("ghost reservations").isZero();
        assertThat(result.successfulUsers).as("successful users").isEqualTo(LIMIT);
    }

    /** Same fixed interruption indices, paused just before the atomic Lua invocation. */
    @Test
    void luaInterruptionBeforeScriptLeavesNoGhostReservations() throws Exception {
        RunResult result = runConcurrent(true, Algorithm.LUA);
        print("lua-fault-before-script", result);

        assertThat(result.aborted).isEqualTo(INTERRUPTED);
        assertThat(result.counter).isEqualTo(LIMIT);
        assertThat(result.participants).isEqualTo(LIMIT);
        assertThat(result.ghosts()).isZero();
        assertThat(result.successfulUsers).isEqualTo(LIMIT);
    }

    @Test
    void compareThroughputAndLatencyForFiveRuns() throws Exception {
        // Warm each strategy on the same Redis instance; warmup results are discarded.
        runConcurrent(false, Algorithm.HISTORICAL_MULTI_COMMANDS);
        runConcurrent(false, Algorithm.LUA);
        List<RunResult> legacy = new ArrayList<>();
        List<RunResult> lua = new ArrayList<>();
        for (int repetition = 1; repetition <= 5; repetition++) {
            legacy.add(runConcurrent(false, Algorithm.HISTORICAL_MULTI_COMMANDS));
            lua.add(runConcurrent(false, Algorithm.LUA));
        }
        System.out.println("REDIS_BENCHMARK warmups=1 repetitions=5 workers=" + WORKERS
                + " requests=" + REQUESTS + " limit=" + LIMIT);
        printSummary("MULTI_COMMANDS", legacy);
        printSummary("LUA", lua);
    }

    private RunResult runConcurrent(boolean injectFaults, Algorithm algorithm) throws Exception {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong totalCommands = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Callable<Outcome>> tasks = java.util.stream.IntStream.range(0, REQUESTS)
                    .mapToObj(index -> (Callable<Outcome>) () -> {
                        ready.countDown();
                        start.await();
                        long began = System.nanoTime();
                        int commands = 0;
                        boolean selectedForInterruption = index < INTERRUPTED;
                        try {
                            if (injectFaults && selectedForInterruption && algorithm == Algorithm.LUA) {
                                // The interruption boundary is immediately before EVAL; no Redis command ran.
                                throw new InjectedInterruption();
                            }
                            if (algorithm == Algorithm.LUA) {
                                commands++;
                                Long result = redis.execute(RESERVATION_SCRIPT,
                                        List.of(USERS, COUNTER), String.valueOf(50_000 + index),
                                        String.valueOf(LIMIT));
                                totalCommands.addAndGet(commands);
                                return new Outcome(result != null && result > 0, false,
                                        System.nanoTime() - began, commands);
                            }

                            if (algorithm == Algorithm.INCR_FIRST_REPRO) {
                                // Request-described variant: INCR -> interruption -> limit check -> SADD.
                                Long count = redis.opsForValue().increment(COUNTER);
                                commands++;
                                if (injectFaults && selectedForInterruption) {
                                    throw new InjectedInterruption();
                                }
                                if (count == null || count > LIMIT) {
                                    redis.opsForValue().decrement(COUNTER);
                                    commands++;
                                    totalCommands.addAndGet(commands);
                                    return new Outcome(false, false, System.nanoTime() - began, commands);
                                }
                                redis.opsForSet().add(USERS, String.valueOf(50_000 + index));
                                commands++;
                                totalCommands.addAndGet(commands);
                                return new Outcome(true, false, System.nanoTime() - began, commands);
                            }

                            // Historical commit 8a100d7: SADD -> INCR -> limit check -> SREM/DECR rollback.
                            Long added = redis.opsForSet().add(USERS, String.valueOf(50_000 + index));
                            commands++;
                            if (added == null || added == 0L) {
                                totalCommands.addAndGet(commands);
                                return new Outcome(false, false, System.nanoTime() - began, commands);
                            }
                            Long count = redis.opsForValue().increment(COUNTER);
                            commands++;
                            if (count == null || count > LIMIT) {
                                redis.opsForSet().remove(USERS, String.valueOf(50_000 + index));
                                redis.opsForValue().decrement(COUNTER);
                                commands += 2;
                                totalCommands.addAndGet(commands);
                                return new Outcome(false, false, System.nanoTime() - began, commands);
                            }
                            totalCommands.addAndGet(commands);
                            return new Outcome(true, false, System.nanoTime() - began, commands);
                        } catch (InjectedInterruption expected) {
                            totalCommands.addAndGet(commands);
                            return new Outcome(false, true, System.nanoTime() - began, commands);
                        }
                    }).toList();
            List<Future<Outcome>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            long wallStart = System.nanoTime();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(REQUESTS);
            for (Future<Outcome> future : futures) outcomes.add(future.get());
            long elapsed = System.nanoTime() - wallStart;
            long counter = parseCounter();
            long participants = valueOrZero(redis.opsForSet().size(USERS));
            long successful = outcomes.stream().filter(Outcome::successful).count();
            long aborted = outcomes.stream().filter(Outcome::aborted).count();
            List<Long> latencies = outcomes.stream().map(Outcome::latencyNanos).sorted().toList();
            long avg = outcomes.stream().mapToLong(Outcome::latencyNanos).sum() / outcomes.size();
            long p95 = latencies.get((int) Math.ceil(latencies.size() * .95) - 1);
            double throughput = REQUESTS / (elapsed / 1_000_000_000.0);
            double rtt = outcomes.stream().mapToInt(Outcome::commands).average().orElse(0);
            return new RunResult(counter, participants, successful, aborted, counter - participants,
                    throughput, avg / 1_000_000.0, p95 / 1_000_000.0, rtt, totalCommands.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private long parseCounter() {
        String raw = redis.opsForValue().get(COUNTER);
        return raw == null ? 0 : Long.parseLong(raw);
    }

    private static long valueOrZero(Long value) { return value == null ? 0 : value; }

    private static void print(String label, RunResult r) {
        System.out.printf("REDIS_FAULT label=%s counter=%d participants=%d ghosts=%d successful=%d aborted=%d%n",
                label, r.counter, r.participants, r.ghosts(), r.successfulUsers, r.aborted);
    }

    private static void printSummary(String label, List<RunResult> results) {
        double throughputAvg = results.stream().mapToDouble(RunResult::throughput).average().orElseThrow();
        double avgLatency = results.stream().mapToDouble(RunResult::averageLatencyMs).average().orElseThrow();
        double p95 = results.stream().mapToDouble(RunResult::p95LatencyMs).average().orElseThrow();
        double rtt = results.stream().mapToDouble(RunResult::redisRttPerRequest).average().orElseThrow();
        for (int i = 0; i < results.size(); i++) {
            RunResult run = results.get(i);
            System.out.printf("REDIS_BENCH_RUN label=%s repetition=%d throughput=%.1f avg_latency_ms=%.3f"
                            + " p95_latency_ms=%.3f redis_rtt_per_request=%.3f%n",
                    label, i + 1, run.throughput, run.averageLatencyMs, run.p95LatencyMs,
                    run.redisRttPerRequest);
        }
        System.out.printf("REDIS_BENCH label=%s throughput_avg=%.1f throughput_range=%.1f..%.1f"
                        + " avg_latency_ms=%.3f avg_latency_range=%.3f..%.3f"
                        + " p95_latency_ms=%.3f p95_latency_range=%.3f..%.3f redis_rtt_per_request=%.3f"
                        + " redis_commands_total_avg=%.1f%n",
                label, throughputAvg,
                results.stream().mapToDouble(RunResult::throughput).min().orElseThrow(),
                results.stream().mapToDouble(RunResult::throughput).max().orElseThrow(),
                avgLatency,
                results.stream().mapToDouble(RunResult::averageLatencyMs).min().orElseThrow(),
                results.stream().mapToDouble(RunResult::averageLatencyMs).max().orElseThrow(),
                p95,
                results.stream().mapToDouble(RunResult::p95LatencyMs).min().orElseThrow(),
                results.stream().mapToDouble(RunResult::p95LatencyMs).max().orElseThrow(),
                rtt,
                results.stream().mapToLong(RunResult::redisCommands).average().orElseThrow());
    }

    private enum Algorithm { HISTORICAL_MULTI_COMMANDS, INCR_FIRST_REPRO, LUA }
    private record Outcome(boolean successful, boolean aborted, long latencyNanos, int commands) { }
    private record RunResult(long counter, long participants, long successfulUsers, long aborted, long ghosts,
                             double throughput, double averageLatencyMs, double p95LatencyMs,
                             double redisRttPerRequest, long redisCommands) { }
    private static class InjectedInterruption extends RuntimeException { }
}
