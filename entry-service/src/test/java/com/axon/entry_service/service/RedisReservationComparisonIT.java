package com.axon.entry_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
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
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisScript<Long> reservationScript;

    @BeforeEach
    void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate();
        redis.setConnectionFactory(connectionFactory);
        redis.afterPropertiesSet();
        EntryReservationService service = new EntryReservationService(redis, mock(ApplicationEventPublisher.class));
        reservationScript = reservationScriptFrom(service);
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
     * Reproduces the assumed INCR-first interruption point described in the original request.
     * It intentionally asserts the desired invariant and is expected to fail,
     * showing the observed ghost count for this injected interruption.
     */
    @Test
    void assumedIncrFirstInterruptionExposesGhostReservations() throws Exception {
        RunResult result = runConcurrent(true, Algorithm.INCR_FIRST_REPRO);
        print("assumed-order-incr-first", result);

        assertThat(result.counter - result.participants).as("ghost reservations").isZero();
        assertThat(result.successfulUsers).as("successful users").isEqualTo(LIMIT);
    }

    /** Same fixed interruption indices, paused just before the atomic Lua invocation. */
    @Test
    void luaInterruptionBeforeScriptLeavesNoGhostReservations() throws Exception {
        RunResult result = runConcurrent(true, Algorithm.LUA);
        print("lua-before-script-assumed-order", result);

        assertThat(result.aborted).isEqualTo(INTERRUPTED);
        assertThat(result.counter).isEqualTo(LIMIT);
        assertThat(result.participants).isEqualTo(LIMIT);
        assertThat(result.ghosts()).isZero();
        assertThat(result.successfulUsers).isEqualTo(LIMIT);
    }

    @Test
    void historicalSaddFirstInterruptionLeavesSetOnlyUsersAndLuaDoesNot() throws Exception {
        Set<Integer> interrupted = indices(0, INTERRUPTED);

        resetRedis();
        WindowResult historical = runFaultWindow(
                Algorithm.HISTORICAL_MULTI_COMMANDS, 0, REQUESTS, interrupted, FaultPoint.AFTER_SADD, 0);
        FaultSummary historicalSummary = summarizeFault("history-after-sadd", historical, 0, interrupted,
                Algorithm.HISTORICAL_MULTI_COMMANDS);
        assertThat(historicalSummary.counter).isEqualTo(LIMIT);
        assertThat(historicalSummary.participants).isEqualTo(LIMIT + INTERRUPTED);
        assertThat(historicalSummary.difference).isEqualTo(-INTERRUPTED);
        assertThat(historicalSummary.successfulUsers).isEqualTo(LIMIT);
        assertThat(historicalSummary.aborted).isEqualTo(INTERRUPTED);
        assertThat(historicalSummary.retryDuplicates).isEqualTo(INTERRUPTED);
        assertThat(historicalSummary.retrySoldOut).isZero();

        resetRedis();
        WindowResult lua = runFaultWindow(Algorithm.LUA, 0, REQUESTS, interrupted, FaultPoint.BEFORE_LUA, 0);
        FaultSummary luaSummary = summarizeFault("lua-after-sadd-comparison", lua, 0, interrupted, Algorithm.LUA);
        assertLuaFullAndRetriesSoldOut(luaSummary);
    }

    @Test
    void historicalOverLimitInterruptionBeforeRollbackLeavesSetOnlyUsersAndLuaDoesNot() throws Exception {
        Set<Integer> interrupted = indices(LIMIT, INTERRUPTED);

        resetRedis();
        WindowResult historical = runFaultWindow(
                Algorithm.HISTORICAL_MULTI_COMMANDS, 0, REQUESTS, interrupted, FaultPoint.AFTER_INCR, LIMIT);
        FaultSummary historicalSummary = summarizeFault("history-over-limit-after-incr", historical, 0, interrupted,
                Algorithm.HISTORICAL_MULTI_COMMANDS);
        assertThat(historicalSummary.counter).isEqualTo(LIMIT + INTERRUPTED);
        assertThat(historicalSummary.participants).isEqualTo(LIMIT + INTERRUPTED);
        assertThat(historicalSummary.difference).isZero();
        assertThat(historicalSummary.successfulUsers).isEqualTo(LIMIT);
        assertThat(historicalSummary.aborted).isEqualTo(INTERRUPTED);
        assertThat(historicalSummary.retryDuplicates).isEqualTo(INTERRUPTED);
        assertThat(historicalSummary.retrySoldOut).isZero();

        resetRedis();
        WindowResult lua = runFaultWindow(Algorithm.LUA, 0, REQUESTS, interrupted, FaultPoint.BEFORE_LUA, LIMIT);
        FaultSummary luaSummary = summarizeFault("lua-over-limit-before-script", lua, 0, interrupted, Algorithm.LUA);
        assertLuaFullAndRetriesSoldOut(luaSummary);
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
                                Long result = redis.execute(reservationScript,
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

    private WindowResult runFaultWindow(Algorithm algorithm, int firstIndex, int requestCount,
                                        Set<Integer> interruptedIndices, FaultPoint faultPoint,
                                        int waitForPrefixCount) throws Exception {
        CountDownLatch ready = new CountDownLatch(Math.min(WORKERS, requestCount));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch prefixCompleted = new CountDownLatch(waitForPrefixCount);
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Callable<Outcome>> tasks = java.util.stream.IntStream.range(firstIndex, firstIndex + requestCount)
                    .mapToObj(index -> (Callable<Outcome>) () -> {
                        boolean prefix = index < firstIndex + waitForPrefixCount;
                        ready.countDown();
                        start.await();
                        if (!prefix) prefixCompleted.await();
                        boolean selected = interruptedIndices.contains(index);
                        try {
                            if (algorithm == Algorithm.LUA) {
                                if (selected) throw new InjectedInterruption();
                                Long result = executeLua(index);
                                return new Outcome(result != null && result > 0, false, 0, 1);
                            }

                            String userId = userId(index);
                            Long added = redis.opsForSet().add(USERS, userId);
                            if (added == null || added == 0L) return new Outcome(false, false, 0, 1);
                            if (selected && faultPoint == FaultPoint.AFTER_SADD) throw new InjectedInterruption();

                            Long count = redis.opsForValue().increment(COUNTER);
                            if (selected && faultPoint == FaultPoint.AFTER_INCR) throw new InjectedInterruption();
                            if (count == null || count > LIMIT) {
                                redis.opsForSet().remove(USERS, userId);
                                redis.opsForValue().decrement(COUNTER);
                                return new Outcome(false, false, 0, 4);
                            }
                            return new Outcome(true, false, 0, 2);
                        } catch (InjectedInterruption expected) {
                            return new Outcome(false, true, 0, 0);
                        } finally {
                            if (prefix) prefixCompleted.countDown();
                        }
                    }).toList();
            List<Future<Outcome>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(requestCount);
            for (Future<Outcome> future : futures) outcomes.add(future.get());
            return new WindowResult(
                    outcomes.stream().filter(Outcome::successful).count(),
                    outcomes.stream().filter(Outcome::aborted).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private FaultSummary summarizeFault(String label, WindowResult window, long priorSuccesses,
                                        Set<Integer> interruptedIndices,
                                        Algorithm algorithm) {
        long counter = parseCounter();
        long participants = valueOrZero(redis.opsForSet().size(USERS));
        long duplicates = 0;
        long soldOut = 0;
        long accepted = 0;
        for (int index : interruptedIndices) {
            if (algorithm == Algorithm.LUA) {
                Long result = executeLua(index);
                if (result != null && result == -1L) duplicates++;
                else if (result != null && result == -2L) soldOut++;
                else if (result != null && result > 0) accepted++;
            } else {
                String userId = userId(index);
                Long added = redis.opsForSet().add(USERS, userId);
                if (added == null || added == 0L) {
                    duplicates++;
                    continue;
                }
                Long count = redis.opsForValue().increment(COUNTER);
                if (count == null || count > LIMIT) {
                    redis.opsForSet().remove(USERS, userId);
                    redis.opsForValue().decrement(COUNTER);
                    soldOut++;
                } else {
                    accepted++;
                }
            }
        }
        FaultSummary summary = new FaultSummary(counter, participants, counter - participants,
                priorSuccesses + window.successfulUsers, window.aborted, duplicates, soldOut, accepted);
        int firstInterruptedId = interruptedIndices.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int lastInterruptedId = interruptedIndices.stream().mapToInt(Integer::intValue).max().orElseThrow();
        System.out.printf("REDIS_HISTORICAL_FAULT label=%s requests=%d limit=%d counter=%d participants=%d"
                        + " counter_minus_set=%d"
                        + " successful=%d aborted=%d retry_attempts=%d retry_rejected=%d"
                        + " retry_duplicate=%d retry_sold_out=%d retry_accepted=%d interrupted_user_ids=%d..%d%n",
                label, REQUESTS, LIMIT, summary.counter, summary.participants, summary.difference, summary.successfulUsers,
                summary.aborted, interruptedIndices.size(), duplicates + soldOut, duplicates, soldOut, accepted,
                50_000 + firstInterruptedId, 50_000 + lastInterruptedId);
        return summary;
    }

    private void resetRedis() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    private Long executeLua(int index) {
        return redis.execute(reservationScript, List.of(USERS, COUNTER), userId(index), String.valueOf(LIMIT));
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<Long> reservationScriptFrom(EntryReservationService service) {
        return (RedisScript<Long>) ReflectionTestUtils.getField(service, "reservationScript");
    }

    private static Set<Integer> indices(int first, int count) {
        Set<Integer> result = new HashSet<>();
        for (int index = first; index < first + count; index++) result.add(index);
        return result;
    }

    private static String userId(int index) { return String.valueOf(50_000 + index); }

    private static void assertLuaFullAndRetriesSoldOut(FaultSummary result) {
        assertThat(result.counter).isEqualTo(LIMIT);
        assertThat(result.participants).isEqualTo(LIMIT);
        assertThat(result.difference).isZero();
        assertThat(result.successfulUsers).isEqualTo(LIMIT);
        assertThat(result.aborted).isEqualTo(INTERRUPTED);
        assertThat(result.retrySoldOut).isEqualTo(INTERRUPTED);
        assertThat(result.retryDuplicates).isZero();
        assertThat(result.retryAccepted).isZero();
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
    private enum FaultPoint { AFTER_SADD, AFTER_INCR, BEFORE_LUA }
    private record Outcome(boolean successful, boolean aborted, long latencyNanos, int commands) { }
    private record WindowResult(long successfulUsers, long aborted) { }
    private record FaultSummary(long counter, long participants, long difference, long successfulUsers, long aborted,
                                long retryDuplicates, long retrySoldOut, long retryAccepted) { }
    private record RunResult(long counter, long participants, long successfulUsers, long aborted, long ghosts,
                             double throughput, double averageLatencyMs, double p95LatencyMs,
                             double redisRttPerRequest, long redisCommands) { }
    private static class InjectedInterruption extends RuntimeException { }
}
