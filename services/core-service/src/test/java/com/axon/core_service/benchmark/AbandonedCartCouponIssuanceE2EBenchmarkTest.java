package com.axon.core_service.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidateStatus;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivity.CouponDeliveryMode;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.commandprocessing.FcfsLedgerPersistenceService;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.repository.AbandonedCartCouponCampaignRepository;
import com.axon.core_service.repository.AbandonedCartCouponCandidateRepository;
import com.axon.core_service.coupon.CouponRepository;
import com.axon.core_service.repository.MarketingActionRepository;
import com.axon.core_service.scheduler.AbandonedCartCouponScheduler;
import com.axon.core_service.service.AbandonedCartCouponCandidateService;
import com.axon.messaging.topic.KafkaTopics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.Hibernate;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/** Opt-in bounded benchmark for the real scheduler-to-UserCoupon path. */
@EnabledIfSystemProperty(named = "abandoned.cart.issuance.e2e", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AbandonedCartCouponIssuanceE2EBenchmarkTest.HarnessConfig.class)
class AbandonedCartCouponIssuanceE2EBenchmarkTest extends AbstractIntegrationTest {

    private static final int CAMPAIGNS = 20;
    private static final int PRODUCTION_BATCH_SIZE = 500;
    private static final int FULL_ROWS = 5_000_000;
    private static final int FULL_DUE = 50_000;
    private static final long TARGET_USER_BASE = 10_000_000L;
    private static final long WARMUP_USER_BASE = 9_000_000L;
    private static final int CUTOFF_SECONDS = 600;
    private static final int OVERLAP_HISTORY_ROWS = 1_000_000;
    private static final int OVERLAP_DUE_ROWS = 50_000;

    @Autowired private AbandonedCartCouponScheduler scheduler;
    @Autowired private MarketingActionRepository actionRepository;
    @Autowired private AbandonedCartCouponCampaignRepository campaignRepository;
    @Autowired private AbandonedCartCouponCandidateRepository candidateRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CampaignActivityRepository activityRepository;
    @Autowired private CampaignRepository activityCampaignRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private FcfsLedgerPersistenceService ledgerPersistenceService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired private DuePageGate pageGate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    @SpyBean private AbandonedCartCouponCandidateService candidateService;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;
    @Value("${spring.datasource.url}") private String jdbcUrl;
    @Value("${spring.datasource.username}") private String jdbcUsername;
    @Value("${spring.datasource.password}") private String jdbcPassword;

    @BeforeEach
    void gateProductionSchedulerAtPageBoundary() throws Exception {
        doAnswer(invocation -> pageGate.allowNextPage()
                        ? invocation.callRealMethod() : List.of())
                .when(candidateService).findDueCandidates(any(LocalDateTime.class), anyInt());
    }

    @Test
    void measuresCommittedCouponIssuanceAtTenMinutes() throws Exception {
        if (Boolean.getBoolean("abandoned.cart.issuance.purchase-overlap")) {
            measuresPurchasePersistenceOverlap();
            return;
        }
        if (Boolean.getBoolean("abandoned.cart.issuance.single-page")) {
            measuresSinglePageDesigns();
            return;
        }
        String arm = System.getProperty("abandoned.cart.issuance.arm", "A");
        boolean smoke = Boolean.getBoolean("abandoned.cart.issuance.smoke");
        int rows = smoke ? 2_000 : Integer.getInteger("abandoned.cart.issuance.rows", FULL_ROWS);
        int dueRows = smoke ? 20 : FULL_DUE;
        int cutoffSeconds = smoke ? 45 : CUTOFF_SECONDS;
        Path artifactDir = Path.of(System.getProperty("abandoned.cart.issuance.artifact-dir",
                "build/benchmark/abandoned-cart-issuance-e2e"));
        Files.createDirectories(artifactDir);
        Path armDir = artifactDir.resolve((smoke ? "smoke-arm-" : "arm-") + arm);
        Files.createDirectories(armDir);
        progress(artifactDir, arm, smoke ? "smoke" : "setup", 0, dueRows);

        stopUnrelatedListeners();
        Map<Long, Long> actionCouponIds = seedCampaigns();
        prepareIndex(arm);
        seedWarmupCandidate(actionCouponIds);
        scheduler.dispatchDueCandidates();
        awaitCouponCount(WARMUP_USER_BASE, WARMUP_USER_BASE + 1, 1, 60);

        progress(artifactDir, arm, smoke ? "smoke" : "seeding", 0, dueRows);
        LocalDateTime seedNow = LocalDateTime.now();
        seedCandidates(rows, dueRows, seedNow);
        jdbc.execute("ANALYZE TABLE abandoned_cart_coupon_candidates");
        assertFixture(rows, dueRows);
        String explain = explainDueQuery();
        Files.writeString(armDir.resolve("explain.txt"), explain);
        Files.writeString(armDir.resolve("candidate-indexes.txt"), jdbc.query(
                "select index_name,seq_in_index,column_name,non_unique from information_schema.statistics"
                        + " where table_schema=database() and table_name='abandoned_cart_coupon_candidates'"
                        + " order by index_name,seq_in_index",
                (rs, row) -> rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getString(3)
                        + ":nonUnique=" + rs.getInt(4)).stream().reduce("", (a, b) -> a + b + "\n"));
        Files.writeString(armDir.resolve("dataset.txt"), "mysqlVersion="
                + jdbc.queryForObject("select version()", String.class)
                + "\nrows=" + rows + "\ndue=" + dueRows + "\nseedNow=" + seedNow
                + "\noverdueAgeAfterDueMinutes=31..90"
                + "\narm=" + arm + "\nindex=" + (arm.equals("B")
                        ? "campaign_id,status,due_at,id" : "none (PK and unique retained)") + "\n");

        long queryNanos = timeOneDueQuery(dueRows);
        progress(artifactDir, arm, smoke ? "smoke" : "timed", 0, dueRows);
        AtomicBoolean cutoffReached = new AtomicBoolean();
        AtomicBoolean snapshotCaptured = new AtomicBoolean();
        AtomicLong issuanceCompletionElapsedSeconds = new AtomicLong(-1);
        CountDownLatch cutoffSnapshotDone = new CountDownLatch(1);
        AtomicReference<ScheduledFuture<?>> cutoffFuture = new AtomicReference<>();
        var timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "issuance-benchmark-cutoff");
            thread.setDaemon(true);
            return thread;
        });
        var worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "issuance-benchmark-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(cutoffSeconds);
        pageGate.openUntil(deadlineNanos);
        Future<?> schedulerRun = worker.submit(scheduler::dispatchDueCandidates);
        timer.scheduleAtFixedRate(() -> {
            try {
                long issued = countCoupons(TARGET_USER_BASE, TARGET_USER_BASE + rows);
                progress(artifactDir, arm, smoke ? "smoke" : "timed",
                        issued, dueRows);
                if (issued >= dueRows && snapshotCaptured.compareAndSet(false, true)) {
                    issuanceCompletionElapsedSeconds.set(
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos));
                    pageGate.close();
                    Snapshot snapshot = snapshot(arm, dueRows, cutoffSeconds, startNanos, queryNanos,
                            issuanceCompletionElapsedSeconds.get())
                            .withSchedulerReturnedBeforeCutoff(schedulerRun.isDone());
                    writeSnapshot(armDir, snapshot);
                    progress(artifactDir, arm, "cutoff", snapshot.issued(), dueRows);
                    ScheduledFuture<?> cutoffTask = cutoffFuture.get();
                    if (cutoffTask != null) cutoffTask.cancel(false);
                    cutoffSnapshotDone.countDown();
                }
            } catch (Exception failure) {
                writeFailure(armDir, failure);
            }
        }, 2, 2, TimeUnit.SECONDS);
        cutoffFuture.set(timer.schedule(() -> {
            cutoffReached.set(true);
            if (snapshotCaptured.compareAndSet(false, true)) {
                pageGate.close();
                try {
                    Snapshot snapshot = snapshot(arm, dueRows, cutoffSeconds, startNanos, queryNanos,
                            issuanceCompletionElapsedSeconds.get())
                            .withSchedulerReturnedBeforeCutoff(schedulerRun.isDone());
                    if (snapshot.issued() >= dueRows) {
                        issuanceCompletionElapsedSeconds.compareAndSet(-1, snapshot.elapsedSeconds());
                        snapshot = snapshot.withIssuanceCompletion(issuanceCompletionElapsedSeconds.get());
                    }
                    writeSnapshot(armDir, snapshot);
                    progress(artifactDir, arm, "cutoff", snapshot.issued(), dueRows);
                } catch (Exception failure) {
                    writeFailure(armDir, failure);
                } finally {
                    cutoffSnapshotDone.countDown();
                }
            }
        }, cutoffSeconds, TimeUnit.SECONDS));

        try {
            schedulerRun.get(cutoffSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            // The scheduled snapshot is taken independently at the exact cutoff.
        }
        cutoffSnapshotDone.await(cutoffSeconds + 60L, TimeUnit.SECONDS);
        stopCouponListener();
        if (!schedulerRun.isDone()) schedulerRun.get(180, TimeUnit.SECONDS);
        Snapshot drained = snapshot(arm, dueRows, cutoffSeconds, startNanos, queryNanos)
                .withSchedulerReturnedBeforeCutoff(schedulerRun.isDone());
        Files.writeString(armDir.resolve("post-cutoff-drain.json"), drained.toJson(true));
        timer.shutdownNow();
        worker.shutdown();
        writeKafkaDiagnostics(armDir);
        assertUniquenessAndSchema(arm);
        progress(artifactDir, arm, smoke ? "smoke" : "done", countCoupons(TARGET_USER_BASE,
                TARGET_USER_BASE + rows), dueRows);
        assertThat(cutoffReached.get() || snapshotCaptured.get()).isTrue();
    }

    private void measuresSinglePageDesigns() throws Exception {
        boolean smoke = Boolean.getBoolean("abandoned.cart.issuance.smoke");
        int rows = smoke ? 100_000 : FULL_ROWS;
        int due = smoke ? 500 : FULL_DUE;
        Path root = Path.of(System.getProperty("abandoned.cart.issuance.artifact-dir",
                "build/benchmark/abandoned-cart-single-page"));
        Files.createDirectories(root);
        pageProgress(root, "setup", "fixture", 0, due, "prepare one shared application context");
        stopUnrelatedListeners();
        seedCampaigns();
        pageProgress(root, "seeding", "fixture", 0, due, "bulk seed history once");
        LocalDateTime seedNow = LocalDateTime.now();
        seedCandidates(rows, due, seedNow);
        assertFixture(rows, due, 0);
        jdbc.update("create table benchmark_page_baseline as select * from abandoned_cart_coupon_candidates"
                + " where status='SCHEDULED' and due_at<=? order by due_at,id limit 500", seedNow);
        jdbc.execute("alter table benchmark_page_baseline add primary key(id), add unique key(user_id)");
        List<Long> expectedIds = jdbc.queryForList("select id from benchmark_page_baseline order by due_at,id", Long.class);
        assertThat(expectedIds).hasSize(PRODUCTION_BATCH_SIZE);
        Map<String, Object> historyBefore = otherCandidateFingerprint();
        AtomicReference<String> design = new AtomicReference<>("A");
        AtomicLong lookupNanos = new AtomicLong();
        AtomicLong lookupStart = new AtomicLong();
        AtomicLong lookupCalls = new AtomicLong();
        doAnswer(invocation -> {
            if (!pageGate.allowNextPage()) return List.of();
            lookupCalls.incrementAndGet();
            lookupStart.set(System.nanoTime());
            @SuppressWarnings("unchecked")
            List<AbandonedCartCouponCandidate> candidates = design.get().equals("A")
                    ? (List<AbandonedCartCouponCandidate>) invocation.callRealMethod()
                    : candidateFirstPage(invocation.getArgument(0), invocation.getArgument(1));
            lookupNanos.set(System.nanoTime() - lookupStart.get());
            assertThat(candidates.stream().map(AbandonedCartCouponCandidate::getId).toList())
                    .containsExactlyElementsOf(expectedIds);
            candidates.forEach(candidate -> {
                assertThat(Hibernate.isInitialized(candidate.getCampaign())).isTrue();
                assertThat(Hibernate.isInitialized(candidate.getCampaign().getMarketingAction())).isTrue();
            });
            return candidates;
        }).when(candidateService).findDueCandidates(any(LocalDateTime.class), anyInt());
        List<PageSample> samples = new ArrayList<>();
        Set<Long> previousExecutionIds = new HashSet<>();
        Map<String, Integer> repetitions = new java.util.HashMap<>();
        String[] order = smoke ? new String[]{"A", "B"} : new String[]{"A", "A", "A", "A", "B", "B", "B", "B"};
        var worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "single-page-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            for (int i = 0; i < order.length; i++) {
                design.set(order[i]);
                boolean warmup = !smoke && (i == 0 || i == 4);
                int repetition = warmup ? 0 : repetitions.merge(design.get(), 1, Integer::sum);
                String label = design.get() + (warmup ? "-warmup" : "-" + repetition);
                preparePageDesign(design.get(), root, expectedIds);
                assertPageRestored(rows, due);
                // Identity verification is outside timing and fully loads the same entities in both designs.
                pageGate.close();
                List<Long> beforeIds = design.get().equals("A")
                        ? realCurrentPageIds() : candidateFirstPage(LocalDateTime.now(), 500).stream()
                                .map(AbandonedCartCouponCandidate::getId).toList();
                assertThat(beforeIds).containsExactlyElementsOf(expectedIds);
                PageKafka beforeKafka = pageKafka(admin);
                assertThat(beforeKafka.lag()).isZero();
                assertThat(beforeKafka.dltRecords()).isZero();
                pageProgress(root, design.get(), label, 0, due, "issue exactly one page and await committed offsets");
                lookupCalls.set(0);
                lookupNanos.set(-1);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                pageGate.openOnePage(deadline);
                Future<?> run = worker.submit(scheduler::dispatchDueCandidates);
                PageKafka lastKafka = null;
                PageSample sample = null;
                long lastProgress = 0;
                while (System.nanoTime() < deadline) {
                    PageCounts counts = pageCounts();
                    if (run.isDone()) run.get();
                    if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(1)) {
                        pageProgress(root, design.get(), label, counts.issued(), due,
                                "await 500 committed coupons, callbacks and stable consumer offsets");
                        lastProgress = System.nanoTime();
                    }
                    if (run.isDone() && counts.complete()) {
                        PageKafka nowKafka = pageKafka(admin);
                        if (nowKafka.lag() == 0 && nowKafka.equals(lastKafka)) {
                            sample = new PageSample(design.get(), repetition, warmup,
                                    lookupNanos.get() / 1_000_000.0,
                                    (System.nanoTime() - lookupStart.get()) / 1_000_000.0,
                                    due, counts.issued(), counts.candidates(), counts.executions(), counts.succeeded(),
                                    0, nowKafka.endOffset() - beforeKafka.endOffset(), nowKafka.lag(), nowKafka.dltRecords());
                            break;
                        }
                        lastKafka = nowKafka;
                    }
                    Thread.sleep(100);
                }
                pageGate.close();
                assertThat(sample).as("%s must complete one real page within 120s", label).isNotNull();
                assertThat(lookupCalls.get()).isEqualTo(1);
                assertThat(sample.commandOffsetAdvance()).isEqualTo(500);
                assertThat(sample.dltRecords()).isZero();
                assertThat(jdbc.queryForObject("select count(*) from (select uc.user_id,uc.coupon_id"
                        + " from user_coupons uc join benchmark_page_baseline b on b.user_id=uc.user_id"
                        + " group by uc.user_id,uc.coupon_id having count(*)>1) duplicates", Long.class)).isZero();
                List<Long> executionIds = jdbc.queryForList("select c.marketing_action_execution_id"
                        + " from abandoned_cart_coupon_candidates c join benchmark_page_baseline b on b.id=c.id", Long.class);
                assertThat(executionIds).hasSize(500).doesNotHaveDuplicates();
                assertThat(executionIds.stream().allMatch(previousExecutionIds::add)).isTrue();
                samples.add(sample);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("sample-" + label + ".json").toFile(), sample);
                restorePage(executionIds);
                assertPageRestored(rows, due);
            }
            assertThat(otherCandidateFingerprint()).isEqualTo(historyBefore);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("identity.json").toFile(),
                    Map.of("orderedCandidateIds", expectedIds, "allSamplesIdentical", true,
                            "allExecutionIdsFresh", true, "otherHistoryFingerprintUnchanged", true));
            writePageSummary(root, samples, rows, due, smoke, seedNow);
            pageProgress(root, "done", "A+B", 500, due, "review summary.json and summary.md");
        } finally {
            pageGate.close();
            stopCouponListener();
            worker.shutdownNow();
        }
    }

    private void measuresPurchasePersistenceOverlap() throws Exception {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        logging.setLogLevel("org.hibernate.SQL", LogLevel.WARN);
        logging.setLogLevel("org.hibernate.orm.jdbc.bind", LogLevel.WARN);
        logging.setLogLevel("org.hibernate.type.descriptor.sql.BasicBinder", LogLevel.WARN);
        logging.setLogLevel("com.axon", LogLevel.WARN);
        boolean smoke = Boolean.getBoolean("abandoned.cart.issuance.smoke");
        int rows = smoke ? 100_000 : OVERLAP_HISTORY_ROWS;
        int due = smoke ? 500 : OVERLAP_DUE_ROWS;
        int warmupSeconds = smoke ? 2 : 10;
        int measuredSeconds = smoke ? 5 : 60;
        Path root = Path.of(System.getProperty("abandoned.cart.issuance.artifact-dir",
                "build/benchmark/abandoned-cart-purchase-overlap"));
        Files.createDirectories(root);
        pageProgress(root, "setup", "fixture", 0, due,
                "seed one disposable MySQL fixture; never use the persistent SQL Lab");
        stopUnrelatedListeners();
        Map<String, Object> mysqlTestcontainerDefaults = mysqlTuning();
        dockerExec(mysqlContainerId(), "sh", "-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql -uroot -e \"$1\"", "sh",
                "SET GLOBAL innodb_buffer_pool_size=134217728; SET GLOBAL table_open_cache=4000; "
                        + "SET GLOBAL sort_buffer_size=262144; SET GLOBAL innodb_redo_log_capacity=104857600;");
        dataSource.getHikariPoolMXBean().softEvictConnections();
        Map<String, Object> mysqlEffectiveSettings = mysqlTuning();
        seedCampaigns();
        long[] purchaseActivity = seedPurchaseActivity();
        pageProgress(root, "seeding", "fixture", 0, due, "seed candidate history once");
        LocalDateTime seedNow = LocalDateTime.now();
        seedCandidates(rows, due, seedNow);
        jdbc.execute("ANALYZE TABLE abandoned_cart_coupon_candidates");
        assertFixture(rows, due, 0);
        jdbc.execute("create table benchmark_overlap_due_baseline as select id,campaign_id,user_id,product_id,status,"
                + "due_at,last_cart_activity_at,claimed_at,marketing_action_execution_id "
                + "from abandoned_cart_coupon_candidates where status='SCHEDULED' and user_id>=" + TARGET_USER_BASE
                + " and user_id<" + (TARGET_USER_BASE + rows));
        jdbc.execute("alter table benchmark_overlap_due_baseline add primary key(id), add unique key(user_id)");
        assertThat(jdbc.queryForObject("select count(*) from benchmark_overlap_due_baseline", Long.class)).isEqualTo((long) due);
        List<Long> firstPageIds = jdbc.queryForList("select id from benchmark_overlap_due_baseline order by due_at,id limit 500", Long.class);
        Map<String, Object> historyBefore = overlapHistoryFingerprint();
        AtomicReference<String> design = new AtomicReference<>("A");
        ConcurrentLinkedQueue<Long> lookupNanos = new ConcurrentLinkedQueue<>();
        AtomicLong lookupCalls = new AtomicLong();
        doAnswer(invocation -> {
            if (!pageGate.allowNextPage()) return List.of();
            long started = System.nanoTime();
            @SuppressWarnings("unchecked")
            List<AbandonedCartCouponCandidate> candidates = design.get().equals("A")
                    ? (List<AbandonedCartCouponCandidate>) invocation.callRealMethod()
                    : candidateFirstPage(invocation.getArgument(0), invocation.getArgument(1));
            lookupNanos.add(System.nanoTime() - started);
            lookupCalls.incrementAndGet();
            if (lookupCalls.get() == 1) {
                assertThat(candidates.stream().map(AbandonedCartCouponCandidate::getId).toList())
                        .containsExactlyElementsOf(firstPageIds);
            }
            candidates.forEach(candidate -> {
                assertThat(Hibernate.isInitialized(candidate.getCampaign())).isTrue();
                assertThat(Hibernate.isInitialized(candidate.getCampaign().getMarketingAction())).isTrue();
            });
            return candidates;
        }).when(candidateService).findDueCandidates(any(LocalDateTime.class), anyInt());

        List<String> order = smoke
                ? List.of("baseline-A", "A-1", "B-1")
                : List.of("baseline-A-1", "A-1", "B-1", "baseline-B-2", "B-2", "A-2");
        List<OverlapWindow> windows = new ArrayList<>();
        long userRangeSequence = 0;
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            for (String label : order) {
                boolean workerEnabled = !label.startsWith("baseline");
                String arm = label.contains("B") ? "B" : "A";
                design.set(arm);
                preparePageDesign(arm, root, firstPageIds);
                restoreOverlapFixture();
                pageGate.close();
                List<Long> identityIds = arm.equals("A") ? realCurrentPageIds()
                        : candidateFirstPage(LocalDateTime.now(), PRODUCTION_BATCH_SIZE).stream()
                                .map(AbandonedCartCouponCandidate::getId).toList();
                assertThat(identityIds).containsExactlyElementsOf(firstPageIds);
                awaitKafkaQuiescence(admin, 30);

                lookupNanos.clear();
                lookupCalls.set(0);
                long warmUserBase = 30_000_000L + userRangeSequence++ * 1_000_000L;
                runOverlapWindow(label + "-warmup", arm, workerEnabled, warmupSeconds,
                        warmUserBase, purchaseActivity[0], purchaseActivity[1], admin,
                        due, false, lookupNanos, lookupCalls);
                restoreOverlapFixture();
                cleanPurchaseWriterRows(warmUserBase, warmUserBase + 2_000_000L);
                awaitKafkaQuiescence(admin, 30);

                lookupNanos.clear();
                lookupCalls.set(0);
                long measuredUserBase = 30_000_000L + userRangeSequence++ * 1_000_000L;
                OverlapWindow window = runOverlapWindow(label, arm, workerEnabled, measuredSeconds,
                        measuredUserBase, purchaseActivity[0], purchaseActivity[1], admin,
                        due, true, lookupNanos, lookupCalls);
                windows.add(window);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                        root.resolve("window-" + label + ".json").toFile(), window);
                restoreOverlapFixture();
                cleanPurchaseWriterRows(measuredUserBase, measuredUserBase + 2_000_000L);
                awaitKafkaQuiescence(admin, 30);
                assertThat(overlapHistoryFingerprint()).isEqualTo(historyBefore);
            }
        } finally {
            pageGate.close();
            stopCouponListener();
        }
        writeOverlapSummary(root, windows, rows, due, warmupSeconds, measuredSeconds, smoke, seedNow,
                mysqlTestcontainerDefaults, mysqlEffectiveSettings);
        objectMapper.writeValue(root.resolve("test-completed.json").toFile(), Map.of(
                "testMethodCompleted", true, "mode", smoke ? "purchase-overlap-smoke" : "purchase-overlap",
                "windows", windows.size(), "completedAt", LocalDateTime.now().toString()));
        pageProgress(root, "done", "baseline+A+B", windows.size(), due,
                "review summary.json and summary.md; no 600-second full run");
    }

    private OverlapWindow runOverlapWindow(String label, String arm, boolean workerEnabled, int seconds,
                                           long userBase, long activityId, long productId, AdminClient admin,
                                           int due, boolean capture, ConcurrentLinkedQueue<Long> lookupNanos,
                                           AtomicLong lookupCalls) throws Exception {
        pageGate.close();
        PageKafka kafkaBefore = pageKafka(admin);
        Map<String, Long> statusBefore = innodbStatus();
        MysqlRuntime runtimeBefore = mysqlRuntime();
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(seconds);
        AtomicLong workerStarted = new AtomicLong();
        AtomicLong workerEnded = new AtomicLong();
        var schedulerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "purchase-overlap-candidate-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> schedulerRun = null;
        PurchaseLoadDriver purchaseLoad = new PurchaseLoadDriver(userBase, activityId, productId, started, deadline);
        Map<String, Long> statusAtCutoff = null;
        MysqlRuntime runtimeAtCutoff = null;
        long cutoffCaptured = 0;
        PurchaseLoadResult result;
        try {
            pageGate.openUntil(workerEnabled ? deadline : 0);
            if (workerEnabled) {
                schedulerRun = schedulerExecutor.submit(() -> {
                    workerStarted.set(System.nanoTime());
                    try {
                        scheduler.dispatchDueCandidates();
                    } finally {
                        workerEnded.set(System.nanoTime());
                    }
                });
            }
            purchaseLoad.start();
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            }
            pageGate.close();
            statusAtCutoff = innodbStatus();
            runtimeAtCutoff = mysqlRuntime();
            cutoffCaptured = System.nanoTime();
        } finally {
            pageGate.close();
            result = purchaseLoad.stop();
            if (schedulerRun != null) schedulerRun.get(60, TimeUnit.SECONDS);
            schedulerExecutor.shutdownNow();
        }
        long ended = System.nanoTime();
        Map<String, Long> statusAfter = statusAtCutoff;
        MysqlRuntime runtimeAfter = runtimeAtCutoff;
        PageKafka kafkaAfter = awaitKafkaQuiescence(admin, 30);
        long issued = jdbc.queryForObject("select count(*) from user_coupons uc join benchmark_overlap_due_baseline b "
                + "on b.user_id=uc.user_id", Long.class);
        long dispatched = jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates c "
                + "join benchmark_overlap_due_baseline b on b.id=c.id where c.status='DISPATCHED'", Long.class);
        long remainingDue = jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates c "
                + "join benchmark_overlap_due_baseline b on b.id=c.id where c.status='SCHEDULED'", Long.class);
        long persistedPurchases = jdbc.queryForObject("select count(*) from purchases where user_id>=? and user_id<?",
                Long.class, userBase, userBase + 2_000_000L);
        long persistedEntries = jdbc.queryForObject("select count(*) from campaign_activity_entries "
                + "where user_id>=? and user_id<?", Long.class, userBase, userBase + 2_000_000L);
        long duplicates = jdbc.queryForObject("select count(*) from (select uc.user_id,uc.coupon_id "
                + "from user_coupons uc join benchmark_overlap_due_baseline b on b.user_id=uc.user_id "
                + "group by uc.user_id,uc.coupon_id having count(*)>1) duplicate_pairs", Long.class);
        double workerActiveSeconds = workerStarted.get() == 0 ? 0
                : Math.max(0, (workerEnded.get() == 0 ? deadline : workerEnded.get())
                        - workerStarted.get()) / 1_000_000_000.0;
        Map<String, Long> statusDelta = new LinkedHashMap<>();
        statusAfter.forEach((key, value) -> statusDelta.put(key, value - statusBefore.getOrDefault(key, 0L)));
        return new OverlapWindow(label, arm, workerEnabled, seconds, due, result,
                percentile(lookupNanos, 0.50), percentile(lookupNanos, 0.95), percentile(lookupNanos, 0.99),
                lookupCalls.get(), new ArrayList<>(lookupNanos), issued, dispatched, remainingDue, duplicates,
                persistedPurchases, persistedEntries,
                kafkaAfter.endOffset() - kafkaBefore.endOffset(), kafkaAfter.lag(),
                kafkaAfter.dltRecords() - kafkaBefore.dltRecords(), workerActiveSeconds,
                statusDelta, runtimeBefore.cpuUsageNanos(), runtimeAfter.cpuUsageNanos(),
                Math.max(0, runtimeAfter.cpuUsageNanos() - runtimeBefore.cpuUsageNanos()) / 1_000_000_000.0,
                (cutoffCaptured - started) / 1_000_000_000.0,
                runtimeAfter.cpuMax(), runtimeAfter.memoryMax(), runtimeAfter.processors(),
                jdbc.queryForObject("select @@innodb_buffer_pool_size", Long.class),
                jdbc.queryForObject("select version()", String.class));
    }

    private long[] seedPurchaseActivity() {
        Campaign campaign = activityCampaignRepository.saveAndFlush(new Campaign("purchase-overlap-benchmark"));
        Product product = productRepository.saveAndFlush(
                new Product("purchase-overlap-benchmark", 1_000_000L, BigDecimal.valueOf(10_000), "benchmark"));
        CampaignActivity activity = activityRepository.saveAndFlush(CampaignActivity.builder()
                .campaign(campaign).product(product).name("purchase-overlap-benchmark")
                .limitCount(Integer.MAX_VALUE).status(CampaignActivityStatus.ACTIVE)
                .startDate(LocalDateTime.now().minusDays(1)).endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.valueOf(10_000)).quantity(1).build());
        return new long[]{activity.getId(), product.getId()};
    }

    private void restoreOverlapFixture() {
        List<Long> executionIds = jdbc.queryForList("select c.marketing_action_execution_id "
                + "from abandoned_cart_coupon_candidates c join benchmark_overlap_due_baseline b on b.id=c.id "
                + "where c.marketing_action_execution_id is not null", Long.class);
        if (!executionIds.isEmpty()) {
            String ids = numericIds(executionIds);
            jdbc.update("delete from user_coupons where marketing_action_execution_id in (" + ids + ")");
            jdbc.update("delete from marketing_action_dispatches where execution_id in (" + ids + ")");
            jdbc.update("delete from marketing_action_executions where id in (" + ids + ")");
        }
        jdbc.update("update abandoned_cart_coupon_candidates c join benchmark_overlap_due_baseline b on b.id=c.id "
                + "set c.status=b.status,c.due_at=b.due_at,c.last_cart_activity_at=b.last_cart_activity_at,"
                + "c.claimed_at=b.claimed_at,c.marketing_action_execution_id=b.marketing_action_execution_id");
        long baselineCount = jdbc.queryForObject("select count(*) from benchmark_overlap_due_baseline", Long.class);
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates c "
                + "join benchmark_overlap_due_baseline b on b.id=c.id where c.status=b.status and c.due_at=b.due_at "
                + "and c.last_cart_activity_at=b.last_cart_activity_at and c.claimed_at<=>b.claimed_at "
                + "and c.marketing_action_execution_id<=>b.marketing_action_execution_id", Long.class))
                .isEqualTo(baselineCount);
        assertThat(jdbc.queryForObject("select count(*) from user_coupons uc join benchmark_overlap_due_baseline b "
                + "on b.user_id=uc.user_id", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from marketing_action_executions e "
                + "join benchmark_overlap_due_baseline b on b.user_id=e.user_id", Long.class)).isZero();
    }

    private void cleanPurchaseWriterRows(long userStart, long userEnd) {
        jdbc.update("delete from purchases where user_id>=? and user_id<?", userStart, userEnd);
        jdbc.update("delete from campaign_activity_entries where user_id>=? and user_id<?", userStart, userEnd);
    }

    private Map<String, Object> overlapHistoryFingerprint() {
        return jdbc.queryForMap("select count(*) rows_kept,sum(c.id) id_sum,"
                + "sum(crc32(concat_ws('|',c.id,c.campaign_id,c.user_id,c.product_id,c.status,c.due_at,"
                + "c.last_cart_activity_at,coalesce(c.claimed_at,'NULL'),coalesce(c.marketing_action_execution_id,-1)))) row_checksum "
                + "from abandoned_cart_coupon_candidates c where not exists(select 1 "
                + "from benchmark_overlap_due_baseline b where b.id=c.id)");
    }

    private PageKafka awaitKafkaQuiescence(AdminClient admin, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        PageKafka previous = null;
        int stable = 0;
        while (System.nanoTime() < deadline) {
            PageKafka current = pageKafka(admin);
            if (current.lag() == 0 && current.equals(previous)) {
                stable++;
                if (stable >= 2) return current;
            } else {
                stable = 0;
            }
            previous = current;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Kafka consumer did not quiesce within " + timeoutSeconds + " seconds");
    }

    private Map<String, Long> innodbStatus() {
        Set<String> selected = Set.of("Innodb_buffer_pool_reads", "Innodb_buffer_pool_read_requests",
                "Innodb_data_read", "Innodb_data_written", "Innodb_data_reads", "Innodb_data_writes",
                "Innodb_os_log_written");
        Map<String, Long> values = new LinkedHashMap<>();
        jdbc.query("show global status", rs -> {
            while (rs.next()) {
                String key = rs.getString(1);
                if (selected.contains(key)) values.put(key, Long.parseLong(rs.getString(2)));
            }
            return null;
        });
        return values;
    }

    private Map<String, Object> mysqlTuning() {
        return jdbc.queryForMap("select @@global.innodb_buffer_pool_size innodb_buffer_pool_size,"
                + "@@global.table_open_cache table_open_cache,@@global.sort_buffer_size sort_buffer_size,"
                + "@@session.sort_buffer_size session_sort_buffer_size,"
                + "@@global.innodb_redo_log_capacity innodb_redo_log_capacity,@@global.innodb_log_file_size innodb_log_file_size");
    }

    private MysqlRuntime mysqlRuntime() throws Exception {
        String containerId = mysqlContainerId();
        String cgroup = dockerExec(containerId, "sh", "-c",
                "cat /sys/fs/cgroup/cpu.stat 2>/dev/null; echo CPU_MAX; cat /sys/fs/cgroup/cpu.max 2>/dev/null; "
                        + "echo MEMORY_MAX; cat /sys/fs/cgroup/memory.max 2>/dev/null; "
                        + "echo CPU_V1; cat /sys/fs/cgroup/cpuacct/cpuacct.usage 2>/dev/null; "
                        + "echo MEMORY_V1; cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null; "
                        + "echo PROCESSORS; nproc");
        long usageNanos = -1;
        String cpuMax = "unavailable";
        String memoryMax = "unavailable";
        String processors = "unavailable";
        boolean v1Cpu = false;
        String section = "v2";
        for (String line : cgroup.split("\\R")) {
            if (line.equals("CPU_MAX")) { section = "cpu"; continue; }
            if (line.equals("MEMORY_MAX")) { section = "memory"; continue; }
            if (line.equals("CPU_V1")) { section = "cpu-v1"; continue; }
            if (line.equals("MEMORY_V1")) { section = "memory-v1"; continue; }
            if (line.equals("PROCESSORS")) { section = "processors"; continue; }
            if (section.equals("v2") && line.startsWith("usage_usec ")) usageNanos = Long.parseLong(line.substring(11)) * 1_000L;
            else if (section.equals("cpu") && !line.isBlank()) cpuMax = line.trim();
            else if (section.equals("memory") && !line.isBlank()) memoryMax = line.trim();
            else if (section.equals("cpu-v1") && !line.isBlank()) { usageNanos = Long.parseLong(line.trim()); v1Cpu = true; }
            else if (section.equals("memory-v1") && !line.isBlank()) memoryMax = line.trim();
            else if (section.equals("processors") && !line.isBlank()) processors = line.trim();
        }
        if (usageNanos < 0) throw new IllegalStateException("MySQL container CPU usage counter unavailable: " + cgroup);
        return new MysqlRuntime(usageNanos, cpuMax, memoryMax, processors, v1Cpu);
    }

    private String mysqlContainerId() throws Exception {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("jdbc:mysql://[^:]+:(\\d+)/").matcher(jdbcUrl);
        if (!matcher.find()) throw new IllegalStateException("Cannot read MySQL Testcontainer port from JDBC URL");
        int mappedPort = Integer.parseInt(matcher.group(1));
        if (mappedPort < 32768 || mappedPort > 60999) {
            throw new IllegalStateException("MySQL JDBC port is not in the dynamic Testcontainers port range");
        }
        String containerId = runCommand("docker", "ps", "--filter", "publish=" + matcher.group(1), "--format", "{{.ID}}").trim();
        if (containerId.isBlank() || containerId.contains("\n")) {
            throw new IllegalStateException("Expected exactly one MySQL Testcontainer for mapped port " + matcher.group(1));
        }
        String identity = runCommand("docker", "inspect", "--format",
                "{{.Config.Image}}|{{json .NetworkSettings.Ports}}",
                containerId).trim();
        if (!identity.startsWith("mysql:8") || !identity.contains("3306/tcp") || !identity.contains(matcher.group(1))) {
            throw new IllegalStateException("Mapped JDBC port is not a disposable Testcontainers MySQL container");
        }
        return containerId;
    }

    private String runCommand(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }

    private String dockerExec(String containerId, String... command) throws Exception {
        String[] full = new String[command.length + 3];
        full[0] = "docker"; full[1] = "exec"; full[2] = containerId;
        System.arraycopy(command, 0, full, 3, command.length);
        return runCommand(full);
    }

    private void writeOverlapSummary(Path root, List<OverlapWindow> windows, int rows, int due,
                                     int warmupSeconds, int measuredSeconds, boolean smoke,
                                     LocalDateTime seedNow, Map<String, Object> mysqlTestcontainerDefaults,
                                     Map<String, Object> mysqlEffectiveSettings) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", smoke ? "purchase-overlap-smoke" : "purchase-overlap");
        summary.put("comparisonBoundary", "A/B query+index plus actual candidate worker load; baseline has no worker");
        summary.put("purchaseWriter", "10 offered 20-message FcfsLedgerPersistenceService.persistBatch transactions/s; synthetic load, no HTTP or Kafka entry-confirm path");
        summary.put("historyRows", rows);
        summary.put("dueRows", due);
        summary.put("duePercent", due * 100.0 / rows);
        summary.put("campaigns", CAMPAIGNS);
        summary.put("campaignsAndDueRows", "due candidates evenly distributed across 20 campaigns");
        summary.put("fixtureDistribution", "seedCandidates formula: due SCHEDULED 5%; future SCHEDULED 4%; CANCELLED 10%; DISPATCHED 81%");
        summary.put("seedNow", seedNow.toString());
        summary.put("batchSize", PRODUCTION_BATCH_SIZE);
        summary.put("warmupSeconds", warmupSeconds);
        summary.put("measuredOfferedSeconds", measuredSeconds);
        summary.put("windowOrder", windows.stream().map(OverlapWindow::label).toList());
        summary.put("mysqlResourceControls", "disposable Testcontainers MySQL; overlap mode normalizes 16MiB test buffer to repository benchmark 128MiB, table_open_cache 4000, sort_buffer_size 262144, redo capacity 100MiB; no CPU/memory quota imposed; persistent SQL Lab not used");
        summary.put("mysqlTestcontainerDefaults", mysqlTestcontainerDefaults);
        summary.put("mysqlEffectiveSettings", mysqlEffectiveSettings);
        summary.put("windows", windows);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("summary.json").toFile(), summary);
        List<String> lines = new ArrayList<>(List.of("# Abandoned-cart worker / Purchase persistence overlap", "",
                "Each measurement is a fixed offered Purchase load; each 20-message ledger call commits Entry and Purchase records through the real Spring transactional service. The driver does not exercise Entry confirm HTTP or Kafka ingress.",
                "A: full entity-graph candidate join plus campaign-leading index. B: due-ID selection plus full JPA fetch with status-leading index. This is a query+index design comparison, not index-only.",
                "Candidate and Purchase tables share the same MySQL Testcontainer. Reads and inserts touch different logical rows/tables; no row-lock contention is inferred. Results concern shared CPU, buffer-pool, storage and redo resources.", "",
                "| Window | Purchase tx p50/p95/p99 ms | Offered / committed / capped | Candidate issue / due remaining | Lookup p50/p95/p99 ms / calls | MySQL CPU seconds / resource seconds |", "| --- | --- | --- | --- | --- | --- |"));
        for (OverlapWindow window : windows) {
            lines.add("| " + window.label() + " | " + window.purchase().endToEndP50Ms() + "/"
                    + window.purchase().endToEndP95Ms() + "/" + window.purchase().endToEndP99Ms()
                    + " | " + window.purchase().offered() + " / " + window.purchase().committed() + " / " + window.purchase().dropped()
                    + " | " + window.issued() + " / " + window.remainingDue()
                    + " | " + window.lookupP50Ms() + "/" + window.lookupP95Ms() + "/" + window.lookupP99Ms()
                    + " / " + window.lookupCalls() + " | " + window.mysqlCpuSeconds() + " / " + window.resourceSeconds() + " |");
        }
        lines.addAll(List.of("", "Purchase percentiles include scheduled-offer-to-commit latency; transaction-only and queue delay percentiles are included in each `window-*.json`. Offers are fixed at 10 transactions/s (200 Purchase rows/s), a synthetic benchmark rate, not an operational rate or SLA.",
                "The same target due rows are restored after each window. B may issue more coupons and therefore perform more downstream writes in the same interval; any Purchase latency difference is a whole-worker comparison, not an isolated query effect.",
                "If the 50,000 target is exhausted before a window ends, the summary retains the worker-active duration; post-exhaustion Purchase observations are not described as concurrent worker interference.",
                "MySQL CPU is the container's cumulative cgroup usage-nanosecond delta, with the measured resource interval in seconds. InnoDB counters are deltas; actual `innodb_buffer_pool_size`, cgroup CPU/memory limits, processor count, and MySQL version are in window artifacts.",
                "No 600-second issuance run was performed. A negative result applies only to this fixture, fixed synthetic Purchase load, default MySQL resources, and measured window."));
        Files.write(root.resolve("summary.md"), lines);
    }

    private static double percentile(Iterable<Long> values, double quantile) {
        List<Long> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        if (sorted.isEmpty()) return 0;
        sorted.sort(Long::compareTo);
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private record MysqlRuntime(long cpuUsageNanos, String cpuMax, String memoryMax,
                                String processors, boolean cgroupV1) { }

    private record PurchaseLoadResult(long offered, long enqueued, long committed, long failed, long dropped,
                                      long expectedOffers, long missingOffers,
                                      double transactionP50Ms, double transactionP95Ms, double transactionP99Ms,
                                      double queueP50Ms, double queueP95Ms, double queueP99Ms,
                                      double endToEndP50Ms, double endToEndP95Ms, double endToEndP99Ms,
                                      double maxQueueDepth, String firstFailure, List<String> rawSamples) { }


    private record PurchaseOffer(long sequence, long offeredAtNanos) { }
    private record PurchaseTxSample(long sequence, long offeredAtNanos, long startedAtNanos, long committedAtNanos) { }

    private record OverlapWindow(String label, String arm, boolean workerEnabled, int measuredSeconds, int dueRows,
                                 PurchaseLoadResult purchase, double lookupP50Ms, double lookupP95Ms,
                                 double lookupP99Ms, long lookupCalls, List<Long> lookupSamplesNanos,
                                 long issued, long dispatched, long remainingDue, long duplicates,
                                 long persistedPurchaseRows, long persistedEntryRows,
                                 long commandRecords, long kafkaLagAfterQuiescence, long dltRecords,
                                 double workerActiveSeconds, Map<String, Long> innodbStatusDelta,
                                 long mysqlCpuStartNanos, long mysqlCpuEndNanos, double mysqlCpuSeconds,
                                 double resourceSeconds, String cgroupCpuMax, String cgroupMemoryMax,
                                 String containerProcessors, long innodbBufferPoolBytes, String mysqlVersion) { }

    private final class PurchaseLoadDriver {
        private final long userBase;
        private final long activityId;
        private final long productId;
        private final long startNanos;
        private final long deadlineNanos;
        private final BlockingQueue<PurchaseOffer> queue = new ArrayBlockingQueue<>(250);
        private final ConcurrentLinkedQueue<PurchaseTxSample> samples = new ConcurrentLinkedQueue<>();
        private final AtomicLong offered = new AtomicLong();
        private final AtomicLong enqueued = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong maxQueueDepth = new AtomicLong();
        private final AtomicReference<String> firstFailure = new AtomicReference<>("");
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final ScheduledExecutorService offerer = Executors.newSingleThreadScheduledExecutor();
        private final Thread writer;

        private PurchaseLoadDriver(long userBase, long activityId, long productId, long startNanos, long deadlineNanos) {
            this.userBase = userBase;
            this.activityId = activityId;
            this.productId = productId;
            this.startNanos = startNanos;
            this.deadlineNanos = deadlineNanos;
            writer = new Thread(this::writeLoop, "purchase-overlap-ledger-writer");
            writer.setDaemon(true);
        }

        private void start() {
            writer.start();
            offerer.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                if (!accepting.get() || now >= deadlineNanos) return;
                long sequence = offered.getAndIncrement();
                long scheduledAt = startNanos + TimeUnit.MILLISECONDS.toNanos(sequence * 100);
                PurchaseOffer offer = new PurchaseOffer(sequence, scheduledAt);
                if (queue.offer(offer)) {
                    enqueued.incrementAndGet();
                    maxQueueDepth.accumulateAndGet(queue.size(), Math::max);
                } else {
                    dropped.incrementAndGet();
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
        }

        private void writeLoop() {
            while (accepting.get() || !queue.isEmpty()) {
                try {
                    PurchaseOffer offer = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (offer == null) continue;
                    long started = System.nanoTime();
                    try {
                        List<CampaignActivityKafkaProducerDto> batch = new ArrayList<>(20);
                        for (int row = 0; row < 20; row++) {
                            batch.add(CampaignActivityKafkaProducerDto.builder()
                                    .userId(userBase + offer.sequence() * 20 + row)
                                    .productId(productId).campaignActivityId(activityId)
                                    .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                                    .quantity(1L).build());
                        }
                        ledgerPersistenceService.persistBatch(batch);
                        samples.add(new PurchaseTxSample(offer.sequence(), offer.offeredAtNanos(), started, System.nanoTime()));
                    } catch (Throwable failure) {
                        failed.incrementAndGet();
                        firstFailure.compareAndSet("", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    }
                } catch (InterruptedException interrupted) {
                    if (!accepting.get()) break;
                    Thread.currentThread().interrupt();
                    failed.incrementAndGet();
                    firstFailure.compareAndSet("", "writer interrupted");
                }
            }
        }

        private PurchaseLoadResult stop() throws InterruptedException {
            accepting.set(false);
            offerer.shutdownNow();
            writer.join(TimeUnit.SECONDS.toMillis(60));
            if (writer.isAlive()) throw new IllegalStateException("Purchase writer did not drain its bounded queue");
            List<Long> tx = new ArrayList<>();
            List<Long> queueDelay = new ArrayList<>();
            List<Long> endToEnd = new ArrayList<>();
            List<String> rawSamples = new ArrayList<>();
            for (PurchaseTxSample sample : samples) {
                long txNanos = sample.committedAtNanos() - sample.startedAtNanos();
                long queueNanos = sample.startedAtNanos() - sample.offeredAtNanos();
                long totalNanos = sample.committedAtNanos() - sample.offeredAtNanos();
                tx.add(txNanos); queueDelay.add(queueNanos); endToEnd.add(totalNanos);
                rawSamples.add(String.format(java.util.Locale.ROOT, "%d,%.3f,%.3f,%.3f", sample.sequence(),
                        txNanos / 1_000_000.0, queueNanos / 1_000_000.0, totalNanos / 1_000_000.0));
            }
            long expectedOffers = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - startNanos) / 100;
            return new PurchaseLoadResult(offered.get(), enqueued.get(), samples.size(), failed.get(), dropped.get(),
                    expectedOffers, Math.max(0, expectedOffers - offered.get()),
                    percentile(tx, .50), percentile(tx, .95), percentile(tx, .99),
                    percentile(queueDelay, .50), percentile(queueDelay, .95), percentile(queueDelay, .99),
                    percentile(endToEnd, .50), percentile(endToEnd, .95), percentile(endToEnd, .99),
                    maxQueueDepth.get(), firstFailure.get(), rawSamples);
        }
    }

    private List<Long> realCurrentPageIds() {
        // Calling the repository directly avoids opening a second scheduler page in the spy.
        return candidateRepository.findByStatusAndDueAtLessThanEqualOrderByDueAtAscIdAsc(
                AbandonedCartCouponCandidateStatus.SCHEDULED, LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, 500)).stream()
                .map(AbandonedCartCouponCandidate::getId).toList();
    }

    private List<AbandonedCartCouponCandidate> candidateFirstPage(LocalDateTime now, int limit) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<Long> ids = entityManager.createQuery("select c.id from AbandonedCartCouponCandidate c"
                            + " where c.status=:status and c.dueAt<=:now order by c.dueAt,c.id", Long.class)
                    .setParameter("status", AbandonedCartCouponCandidateStatus.SCHEDULED)
                    .setParameter("now", now).setMaxResults(limit).getResultList();
            if (ids.isEmpty()) return List.of();
            var candidates = entityManager.createQuery("select c from AbandonedCartCouponCandidate c"
                            + " join fetch c.campaign campaign join fetch campaign.marketingAction where c.id in :ids",
                            AbandonedCartCouponCandidate.class).setParameter("ids", ids).getResultList();
            Map<Long, AbandonedCartCouponCandidate> byId = candidates.stream().collect(
                    java.util.stream.Collectors.toMap(AbandonedCartCouponCandidate::getId, candidate -> candidate));
            return ids.stream().map(byId::get).toList();
        });
    }

    private void preparePageDesign(String design, Path root, List<Long> ids) throws Exception {
        String index = design.equals("A") ? "idx_abandoned_cart_candidate_campaign_due" : "idx_benchmark_candidate_due";
        if (jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database()"
                + " and table_name='abandoned_cart_coupon_candidates' and index_name=?", Integer.class, index) == 0) {
            String other = design.equals("A") ? "idx_benchmark_candidate_due" : "idx_abandoned_cart_candidate_campaign_due";
            jdbc.execute("drop index " + other + " on abandoned_cart_coupon_candidates");
            jdbc.execute("create index " + index + " on abandoned_cart_coupon_candidates "
                    + (design.equals("A") ? "(campaign_id,status,due_at,id)" : "(status,due_at,id)"));
        }
        jdbc.execute("analyze table abandoned_cart_coupon_candidates,abandoned_cart_coupon_campaigns,marketing_actions");
        Files.writeString(root.resolve("indexes-" + design + ".txt"), jdbc.queryForList(
                "select index_name,seq_in_index,column_name,non_unique from information_schema.statistics"
                        + " where table_schema=database() and table_name='abandoned_cart_coupon_candidates'"
                        + " order by index_name,seq_in_index").toString());
        if (!Files.exists(root.resolve("explain-" + design + ".txt"))) {
            String plan = design.equals("A") ? explainDueQuery() : jdbc.queryForList(
                    "explain analyze select id from abandoned_cart_coupon_candidates where status='SCHEDULED'"
                            + " and due_at<=? order by due_at,id limit 500", LocalDateTime.now()).toString()
                    + "\nFull entity fetch:\n" + jdbc.queryForList("explain analyze select c.*,ac.*,ma.*"
                            + " from abandoned_cart_coupon_candidates c join abandoned_cart_coupon_campaigns ac"
                            + " on ac.id=c.campaign_id join marketing_actions ma on ma.id=ac.marketing_action_id"
                            + " where c.id in (" + numericIds(ids) + ")").toString();
            Files.writeString(root.resolve("explain-" + design + ".txt"), plan);
        }
    }

    private PageCounts pageCounts() {
        String scope = " from abandoned_cart_coupon_candidates c join benchmark_page_baseline b on b.id=c.id";
        return new PageCounts(jdbc.queryForObject("select count(*)" + scope
                + " join user_coupons uc on uc.marketing_action_execution_id=c.marketing_action_execution_id"
                + " and uc.user_id=b.user_id", Long.class),
                jdbc.queryForObject("select count(*)" + scope + " where c.status='DISPATCHED'", Long.class),
                jdbc.queryForObject("select count(*)" + scope
                        + " join marketing_action_executions e on e.id=c.marketing_action_execution_id", Long.class),
                jdbc.queryForObject("select count(*)" + scope
                        + " join marketing_action_dispatches d on d.execution_id=c.marketing_action_execution_id"
                        + " where d.status='SUCCEEDED' and d.completed_at is not null", Long.class));
    }

    private void assertPageRestored(int rows, int due) {
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates", Long.class)).isEqualTo(rows);
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates"
                + " where status='SCHEDULED' and due_at<=?", Long.class, LocalDateTime.now())).isEqualTo(due);
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates c"
                + " join benchmark_page_baseline b on b.id=c.id where c.status=b.status"
                + " and c.due_at=b.due_at and c.last_cart_activity_at=b.last_cart_activity_at"
                + " and c.claimed_at<=>b.claimed_at and c.marketing_action_execution_id<=>b.marketing_action_execution_id",
                Long.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from user_coupons uc join benchmark_page_baseline b"
                + " on b.user_id=uc.user_id", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from marketing_action_executions e join benchmark_page_baseline b"
                + " on b.user_id=e.user_id", Long.class)).isZero();
    }

    private void restorePage(List<Long> executions) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String ids = numericIds(executions);
            assertThat(jdbc.update("delete from user_coupons where marketing_action_execution_id in (" + ids + ")")).isEqualTo(500);
            assertThat(jdbc.update("delete from marketing_action_dispatches where execution_id in (" + ids + ")")).isEqualTo(500);
            assertThat(jdbc.update("delete from marketing_action_executions where id in (" + ids + ")")).isEqualTo(500);
            assertThat(jdbc.update("update abandoned_cart_coupon_candidates c join benchmark_page_baseline b on b.id=c.id"
                    + " set c.status=b.status,c.claimed_at=b.claimed_at,c.marketing_action_execution_id=b.marketing_action_execution_id,"
                    + "c.due_at=b.due_at,c.last_cart_activity_at=b.last_cart_activity_at")).isEqualTo(500);
        });
    }

    private Map<String, Object> otherCandidateFingerprint() {
        return jdbc.queryForMap("select count(*) rows_kept,sum(c.id) id_sum,"
                + "sum(crc32(concat_ws('|',c.id,c.campaign_id,c.user_id,c.product_id,c.status,c.due_at,"
                + "c.last_cart_activity_at,coalesce(c.claimed_at,'NULL'),coalesce(c.marketing_action_execution_id,-1)))) row_checksum"
                + " from abandoned_cart_coupon_candidates c where not exists(select 1 from benchmark_page_baseline b where b.id=c.id)");
    }

    private PageKafka pageKafka(AdminClient admin) throws Exception {
        var command = admin.describeTopics(List.of(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND)).allTopicNames()
                .get(5, TimeUnit.SECONDS).get(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND);
        var request = new java.util.HashMap<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.admin.OffsetSpec>();
        command.partitions().forEach(partition -> request.put(new org.apache.kafka.common.TopicPartition(
                KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, partition.partition()), org.apache.kafka.clients.admin.OffsetSpec.latest()));
        long dlt = 0;
        if (admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT)) {
            var partitions = admin.describeTopics(List.of(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT)).allTopicNames()
                    .get(5, TimeUnit.SECONDS).get(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT).partitions();
            for (var partition : partitions) {
                var tp = new org.apache.kafka.common.TopicPartition(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, partition.partition());
                dlt += admin.listOffsets(Map.of(tp, org.apache.kafka.clients.admin.OffsetSpec.latest()))
                        .all().get(5, TimeUnit.SECONDS).get(tp).offset();
            }
        }
        var ends = admin.listOffsets(request).all().get(5, TimeUnit.SECONDS);
        var committed = admin.listConsumerGroupOffsets("axon-group").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        long end = ends.values().stream().mapToLong(value -> value.offset()).sum();
        long offset = request.keySet().stream().mapToLong(tp -> committed.containsKey(tp) ? committed.get(tp).offset() : 0).sum();
        return new PageKafka(end, offset, end - offset, dlt);
    }

    private void writePageSummary(Path root, List<PageSample> samples, int rows, int due, boolean smoke,
                                  LocalDateTime seedNow) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", smoke ? "single-page-smoke" : "single-page-full");
        summary.put("comparison", "query+index designs: A production entity-graph JOIN with campaign-leading index; B due IDs with status-leading index plus full JPA fetch and order restoration");
        summary.put("historyRows", rows);
        summary.put("dueBeforeEverySample", due);
        summary.put("seedNow", seedNow.toString());
        summary.put("overdueAgeAfterDueMinutes", "31..90");
        summary.put("quiescencePollingMillis", 100);
        summary.put("sampleCapSeconds", 120);
        summary.put("protocol", "Grouped A warm-up + 3 samples, then B warm-up + 3 samples; one index replacement. One untimed full-entity identity lookup before every sample. Grouped order allows cache/time bias.");
        summary.put("mysqlVersion", jdbc.queryForObject("select version()", String.class));
        summary.put("innodbBufferPoolBytes", jdbc.queryForObject("select @@innodb_buffer_pool_size", Long.class));
        summary.put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
        summary.put("resources", "one shared MySQL/Kafka/Redis Testcontainers fixture and Spring context; one scheduler runner; actual axon-group consumer");
        summary.put("samples", samples);
        summary.put("duplicates", 0);
        summary.put("identityAndRestorationAssertionsPassed", true);
        summary.put("limitation", "Repeated same page on warm buffers, not a full drain simulation. Any 50K projection is only 100 times sample median; it does not prove 600s compliance or replace the recorded 16000 vs 34798 result.");
        List<String> lines = new ArrayList<>(List.of("# Abandoned-cart single-page comparison", "",
                "Mode: " + summary.get("mode") + "; one shared seed, same ordered 500 restored between samples.",
                "A: production entity-graph JOIN + (campaign_id,status,due_at,id). B: IDs + (status,due_at,id), then real JPA fetch and order restoration.", "",
                "| Design | Lookup ms (3 samples) | Total 500 issuance ms (3 samples) | Lookup median ms | Total median ms | Rough 50K projection seconds |",
                "| --- | --- | --- | ---: | ---: | ---: |"));
        for (String design : List.of("A", "B")) {
            var measured = samples.stream().filter(sample -> sample.design().equals(design) && !sample.warmup()).toList();
            var lookups = measured.stream().map(PageSample::lookupMillis).toList();
            var totals = measured.stream().map(PageSample::total500IssuanceMillis).toList();
            double lookupMedian = lookups.stream().sorted().skip(lookups.size() / 2).findFirst().orElseThrow();
            double totalMedian = totals.stream().sorted().skip(totals.size() / 2).findFirst().orElseThrow();
            summary.put(design, Map.of("lookupMillis", lookups, "total500IssuanceMillis", totals,
                    "lookupMedianMillis", lookupMedian, "totalMedianMillis", totalMedian,
                    "rough50kProjectionSeconds", 100 * totalMedian / 1000));
            lines.add("| " + design + " | " + lookups + " | " + totals + " | " + lookupMedian + " | " + totalMedian
                    + " | " + 100 * totalMedian / 1000 + " |");
        }
        lines.addAll(List.of("", "Every sample: 500 committed coupons, 500 DISPATCHED candidates, 500 fresh executions and SUCCEEDED dispatches; one lookup page, 500 Kafka records, lag 0 after live consumer quiescence, DLT 0, duplicates 0.",
                "Timings use System.nanoTime; completion observation resolution is 100ms plus query/AdminClient time. Total includes all scheduler callbacks and stable committed consumer offsets. Warm-ups are excluded.",
                "Grouped A warm-up + 3, then B warm-up + 3; one index replacement, each sample preceded by full-entity identity lookup. Grouped order allows cache/time bias.",
                "MySQL " + summary.get("mysqlVersion") + "; history=" + rows + "; due=" + due + "; seed due age=31..90 minutes. Default optimized plans, no FORCE INDEX.",
                summary.get("limitation").toString()));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("summary.json").toFile(), summary);
        Files.write(root.resolve("summary.md"), lines);
    }

    private void pageProgress(Path root, String stage, String sample, long issued, int due, String next) throws Exception {
        objectMapper.writeValue(root.resolve("progress.json").toFile(), Map.of("stage", stage, "sample", sample,
                "issued", issued, "sampleSize", 500, "targetDue", due, "childPid", ProcessHandle.current().pid(),
                "sampleCapSeconds", 120, "nextStep", next, "updatedAt", LocalDateTime.now().toString()));
    }

    private static String numericIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private record PageCounts(long issued, long candidates, long executions, long succeeded) {
        boolean complete() { return issued == 500 && candidates == 500 && executions == 500 && succeeded == 500; }
    }

    private record PageKafka(long endOffset, long committedOffset, long lag, long dltRecords) { }

    private record PageSample(String design, int repetition, boolean warmup, double lookupMillis,
                              double total500IssuanceMillis, int targetDueBefore, long committedUserCoupons,
                              long completedCandidates, long executions, long succeededDispatches,
                              long duplicateUserCouponPairs, long commandOffsetAdvance,
                              long kafkaLagAfterQuiescence, long dltRecords) { }

    private void stopUnrelatedListeners() {
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (container.isRunning()) container.stop();
        });
        // The production command listener is the only listener needed for issuance.
        listenerRegistry.getListenerContainers().stream()
                .filter(this::isCouponCommandConsumer)
                .findFirst().ifPresent(container -> container.start());
        assertThat(listenerRegistry.getListenerContainers().stream()
                .anyMatch(container -> isCouponCommandConsumer(container) && container.isRunning())).isTrue();
    }

    private void stopCouponListener() {
        listenerRegistry.getListenerContainers().stream()
                .filter(this::isCouponCommandConsumer)
                .findFirst().ifPresent(container -> {
                    if (container.isRunning()) container.stop();
                });
    }

    private boolean isCouponCommandConsumer(org.springframework.kafka.listener.MessageListenerContainer container) {
        var properties = container.getContainerProperties();
        return "axon-group".equals(properties.getGroupId())
                && properties.getTopics() != null
                && java.util.Arrays.asList(properties.getTopics()).contains(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND);
    }

    private Map<Long, Long> seedCampaigns() {
        List<Coupon> coupons = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < CAMPAIGNS; i++) {
            coupons.add(Coupon.builder().name("issuance-benchmark-" + i)
                    .discountAmount(BigDecimal.ONE).startDate(now.minusDays(1)).endDate(now.plusDays(30)).build());
        }
        List<Coupon> savedCoupons = couponRepository.saveAllAndFlush(coupons);
        java.util.HashMap<Long, Long> mapping = new java.util.HashMap<>();
        for (int i = 0; i < CAMPAIGNS; i++) {
            MarketingAction action = actionRepository.saveAndFlush(MarketingAction.builder()
                    .actionType(RewardType.COUPON).referenceId(savedCoupons.get(i).getId()).isActive(true).build());
            Campaign parentCampaign = activityCampaignRepository.saveAndFlush(Campaign.builder()
                    .name("issuance-benchmark-parent-" + i).build());
            CampaignActivity activity = activityRepository.saveAndFlush(CampaignActivity.builder()
                    .campaign(parentCampaign).name("issuance-benchmark-activity-" + i)
                    .status(CampaignActivityStatus.ACTIVE).startDate(now.minusDays(1)).endDate(now.plusDays(30))
                    .activityType(CampaignActivityType.COUPON).couponDeliveryMode(CouponDeliveryMode.ABANDONED_CART)
                    .coupon(savedCoupons.get(i)).marketingAction(action)
                    .price(BigDecimal.ZERO).quantity(0).build());
            AbandonedCartCouponCampaign campaign = campaignRepository.saveAndFlush(
                    AbandonedCartCouponCampaign.builder().campaignActivity(activity).marketingAction(action)
                            .delayMinutes(30).isActive(true).build());
            mapping.put(campaign.getId(), savedCoupons.get(i).getId());
        }
        return mapping;
    }

    private void prepareIndex(String arm) {
        jdbc.execute("DROP INDEX idx_abandoned_cart_candidate_campaign_due"
                + " ON abandoned_cart_coupon_candidates");
        if (arm.equals("A")) {
            // Baseline preserves the primary and candidate identity unique keys.
        } else if (arm.equals("B")) {
            jdbc.execute("CREATE INDEX idx_abandoned_cart_candidate_campaign_due ON abandoned_cart_coupon_candidates"
                    + " (campaign_id,status,due_at,id)");
        } else {
            throw new IllegalArgumentException("arm must be A or B");
        }
        jdbc.execute("ANALYZE TABLE abandoned_cart_coupon_candidates");
        int dueIndexCount = jdbc.queryForObject("select count(*) from information_schema.statistics"
                + " where table_schema=database() and table_name='abandoned_cart_coupon_candidates'"
                + " and index_name='idx_abandoned_cart_candidate_campaign_due'", Integer.class);
        assertThat(dueIndexCount > 0).isEqualTo(arm.equals("B"));
    }

    private void seedWarmupCandidate(Map<Long, Long> mapping) {
        Long campaignId = jdbc.queryForObject("select id from abandoned_cart_coupon_campaigns order by id limit 1", Long.class);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into abandoned_cart_coupon_candidates(campaign_id,user_id,product_id,last_cart_activity_at,due_at,status)"
                        + " values (?,?,?,?,?,'SCHEDULED')", campaignId, WARMUP_USER_BASE, 900_001L,
                now.minusMinutes(31), now.minusMinutes(1));
    }

    private void seedCandidates(int rows, int dueRows, LocalDateTime seedNow) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", jdbcUsername);
        properties.setProperty("password", jdbcPassword);
        properties.setProperty("rewriteBatchedStatements", "true");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties)) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("insert into abandoned_cart_coupon_candidates"
                    + "(campaign_id,user_id,product_id,last_cart_activity_at,due_at,status) values (?,?,?,?,?,?)")) {
                for (int row = 0; row < rows; row++) {
                    int bucket = row % 100;
                    boolean due = row % (rows / dueRows) == 0 && row < dueRows * (rows / dueRows);
                    long campaignId = 1L + ((row / 100) % CAMPAIGNS);
                    String status;
                    LocalDateTime dueAt;
                    if (due) {
                        status = "SCHEDULED";
                        dueAt = seedNow.minusMinutes(31 + (row % 60));
                    } else if (bucket < 5) {
                        status = "SCHEDULED";
                        dueAt = seedNow.plusDays(30).plusMinutes(row % 60);
                    } else if (bucket < 15) {
                        status = "CANCELLED";
                        dueAt = seedNow.minusDays(30);
                    } else {
                        status = "DISPATCHED";
                        dueAt = seedNow.minusDays(365);
                    }
                    long userId = due ? TARGET_USER_BASE + row : row + 1L;
                    insert.setLong(1, campaignId);
                    insert.setLong(2, userId);
                    insert.setLong(3, 100_000L + row);
                    insert.setObject(4, dueAt.minusMinutes(30));
                    insert.setObject(5, dueAt);
                    insert.setString(6, status);
                    insert.addBatch();
                    if ((row + 1) % 5_000 == 0) {
                        insert.executeBatch();
                        connection.commit();
                    }
                }
                insert.executeBatch();
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void assertFixture(int rows, int dueRows) {
        assertFixture(rows, dueRows, 1);
    }

    private void assertFixture(int rows, int dueRows, int warmupRows) {
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates", Long.class))
                .isEqualTo((long) rows + warmupRows);
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates"
                + " where status='SCHEDULED' and due_at <= ?", Long.class, LocalDateTime.now()))
                .isEqualTo((long) dueRows);
        assertThat(jdbc.queryForObject("select count(distinct user_id) from abandoned_cart_coupon_candidates"
                + " where user_id >= ?", Long.class, TARGET_USER_BASE)).isEqualTo((long) dueRows);
        assertThat(jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates c"
                + " join abandoned_cart_coupon_campaigns ac on ac.id=c.campaign_id and ac.is_active=true"
                + " join marketing_actions ma on ma.id=ac.marketing_action_id and ma.is_active=true"
                + " join coupons cp on cp.id=ma.reference_id where c.user_id>=? and c.user_id<?"
                + " and c.status='SCHEDULED' and c.due_at<=?", Long.class,
                TARGET_USER_BASE, TARGET_USER_BASE + rows, LocalDateTime.now())).isEqualTo((long) dueRows);
        assertThat(jdbc.queryForObject("select count(*) from purchases where user_id>=? and user_id<?",
                Long.class, TARGET_USER_BASE, TARGET_USER_BASE + rows)).isZero();
        if (rows == FULL_ROWS) {
            assertThat(jdbc.queryForList("select campaign_id from abandoned_cart_coupon_candidates"
                    + " where user_id>=? group by campaign_id having count(*)<>2500", Long.class,
                    TARGET_USER_BASE)).isEmpty();
        }
    }

    private long timeOneDueQuery(int dueRows) {
        long start = System.nanoTime();
        assertThat(candidateService.findDueCandidates(LocalDateTime.now(), PRODUCTION_BATCH_SIZE))
                .hasSize(Math.min(PRODUCTION_BATCH_SIZE, dueRows));
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private Snapshot snapshot(String arm, int dueRows, int cutoff, long startNanos, long sqlMillis) {
        return snapshot(arm, dueRows, cutoff, startNanos, sqlMillis, -1);
    }

    private Snapshot snapshot(String arm, int dueRows, int cutoff, long startNanos, long sqlMillis,
                              long issuanceCompletionSeconds) {
        long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
        long issued = countCoupons(TARGET_USER_BASE, TARGET_USER_BASE + FULL_ROWS);
        List<String> candidateStates = jdbc.query("select status,count(*) count from abandoned_cart_coupon_candidates"
                + " group by status order by status", (rs, row) -> rs.getString(1) + "=" + rs.getLong(2));
        long outstandingCandidates = jdbc.queryForObject("select count(*) from abandoned_cart_coupon_candidates"
                + " where status in ('SCHEDULED','PROCESSING')", Long.class);
        List<String> dispatchStates = jdbc.query("select d.status,count(*) count from marketing_action_dispatches d"
                + " join marketing_action_executions e on e.id=d.execution_id where e.user_id>=? and e.user_id<?"
                + " group by d.status order by d.status", (rs, row) -> rs.getString(1) + "=" + rs.getLong(2),
                TARGET_USER_BASE, TARGET_USER_BASE + FULL_ROWS);
        long duplicates = jdbc.queryForObject("select count(*) from (select user_id,coupon_id from user_coupons"
                + " where user_id>=? and user_id<? group by user_id,coupon_id having count(*)>1) d",
                Long.class, TARGET_USER_BASE, TARGET_USER_BASE + FULL_ROWS);
        long completedAt = issuanceCompletionSeconds >= 0 ? issuanceCompletionSeconds
                : issued >= dueRows ? elapsed : -1;
        return new Snapshot(arm, cutoff, dueRows, issued, duplicates, elapsed, sqlMillis,
                candidateStates, dispatchStates, outstandingCandidates, completedAt, false);
    }

    private long countCoupons(long minUser, long maxUser) {
        return jdbc.queryForObject("select count(*) from user_coupons where user_id>=? and user_id<?",
                Long.class, minUser, maxUser);
    }

    private void awaitCouponCount(long minUser, long maxUser, long expected, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (countCoupons(minUser, maxUser) == expected) return;
            Thread.sleep(250);
        }
        assertThat(countCoupons(minUser, maxUser)).isEqualTo(expected);
    }

    private String explainDueQuery() {
        return jdbc.query("explain analyze select c.id,c.campaign_id,c.user_id,c.product_id,c.last_cart_activity_at,"
                + "c.due_at,c.status,c.claimed_at,c.marketing_action_execution_id,ac.id,ac.marketing_action_id,"
                + "ac.delay_minutes,ac.is_active,ma.id,ma.action_type,ma.reference_id,ma.is_active from"
                + " abandoned_cart_coupon_candidates c join abandoned_cart_coupon_campaigns ac on ac.id=c.campaign_id"
                + " join marketing_actions ma on ma.id=ac.marketing_action_id where c.status='SCHEDULED' and c.due_at<=?"
                + " order by c.due_at,c.id limit 500", ps -> ps.setObject(1, LocalDateTime.now()),
                (rs, row) -> rs.getString(1))
                .stream().reduce("", (left, right) -> left + right + "\n");
    }

    private void writeKafkaDiagnostics(Path armDir) throws Exception {
        org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(
                Map.of("bootstrap.servers", bootstrapServers));
        try {
            var availableTopics = admin.listTopics().names().get(15, TimeUnit.SECONDS);
            List<String> measuredTopics = List.of("axon.campaign-activity.command",
                    "axon.campaign-activity.command.dlt").stream().filter(availableTopics::contains).toList();
            var topics = admin.describeTopics(measuredTopics)
                    .allTopicNames().get(15, TimeUnit.SECONDS);
            var topicPartitions = topics.entrySet().stream().flatMap(entry -> entry.getValue().partitions().stream()
                    .map(partition -> new org.apache.kafka.common.TopicPartition(
                            entry.getKey(), partition.partition()))).toList();
            var latestRequest = topicPartitions.stream().collect(java.util.stream.Collectors.toMap(
                    partition -> partition, ignored -> org.apache.kafka.clients.admin.OffsetSpec.latest()));
            var latest = admin.listOffsets(latestRequest).all().get(15, TimeUnit.SECONDS);
            var groupOffsets = admin.listConsumerGroupOffsets("axon-group").partitionsToOffsetAndMetadata()
                    .get(15, TimeUnit.SECONDS);
            long commandLag = topicPartitions.stream()
                    .filter(partition -> partition.topic().equals("axon.campaign-activity.command"))
                    .mapToLong(partition -> latest.get(partition).offset()
                            - (groupOffsets.containsKey(partition) ? groupOffsets.get(partition).offset() : 0L))
                    .sum();
            long dltRecords = topicPartitions.stream()
                    .filter(partition -> partition.topic().equals("axon.campaign-activity.command.dlt"))
                    .mapToLong(partition -> latest.get(partition).offset()).sum();
            Files.writeString(armDir.resolve("kafka-topics.txt"), "commandLag=" + commandLag
                    + "\ndltEndOffsetRecords=" + dltRecords + "\npartitions=" + topicPartitions + "\n");
        } finally {
            admin.close();
        }
    }

    private void assertUniquenessAndSchema(String arm) {
        assertThat(jdbc.queryForObject("select count(*) from (select user_id,coupon_id from user_coupons"
                + " group by user_id,coupon_id having count(*)>1) d", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database()"
                + " and table_name='abandoned_cart_coupon_candidates' and index_name='uk_abandoned_cart_coupon_candidate'",
                Integer.class)).isEqualTo(3);
        int dueIndexCount = jdbc.queryForObject("select count(*) from information_schema.statistics"
                + " where table_schema=database() and table_name='abandoned_cart_coupon_candidates'"
                + " and index_name='idx_abandoned_cart_candidate_campaign_due'", Integer.class);
        assertThat(dueIndexCount > 0).isEqualTo(arm.equals("B"));
    }

    private void writeSnapshot(Path armDir, Snapshot snapshot) throws Exception {
        Files.writeString(armDir.resolve("cutoff.json"), snapshot.toJson(false));
        Files.writeString(armDir.resolve("cutoff.md"), snapshot.toMarkdown());
    }

    private void progress(Path root, String arm, String stage, long issued, int due) throws Exception {
        String next = stage.equals("done") ? "write final A/B summary"
                : stage.equals("cutoff") ? "stop benchmark consumer and record post-cutoff drain"
                : stage.equals("timed") ? "sample committed target UserCoupon rows until cutoff"
                : stage.equals("seeding") ? "bulk seed target candidate history"
                : "prepare fixtures and uncounted warm-up";
        Files.writeString(root.resolve("progress.json"), "{\"stage\":\"" + stage + "\",\"arm\":\"" + arm
                + "\",\"childPid\":" + ProcessHandle.current().pid() + ",\"issued\":" + issued
                + ",\"targetDue\":" + due + ",\"boundary\":\"scheduler to committed target UserCoupon rows\""
                + ",\"cutoffSeconds\":" + (Boolean.getBoolean("abandoned.cart.issuance.smoke") ? 45 : 600)
                + ",\"nextStep\":\"" + next + "\",\"blocker\":null,\"updatedAt\":\""
                + LocalDateTime.now() + "\"}\n");
    }

    private void writeFailure(Path armDir, Exception failure) {
        try { Files.writeString(armDir.resolve("snapshot-error.txt"), failure.toString()); }
        catch (Exception ignored) { }
    }

    @TestConfiguration
    static class HarnessConfig implements SchedulingConfigurer {
        @Bean DuePageGate duePageGate() { return new DuePageGate(); }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.setTaskScheduler(new DisabledTaskScheduler());
        }
    }

    private static final class DisabledTaskScheduler implements TaskScheduler {
        @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) { return new NeverScheduledFuture(); }
        @Override public ScheduledFuture<?> schedule(Runnable task, Instant startTime) { return new NeverScheduledFuture(); }
        @Override public ScheduledFuture<?> schedule(Runnable task, Date startTime) { return new NeverScheduledFuture(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return new NeverScheduledFuture();
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return new NeverScheduledFuture();
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return new NeverScheduledFuture();
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return new NeverScheduledFuture();
        }
    }

    private static final class NeverScheduledFuture implements ScheduledFuture<Object> {
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicBoolean isCancelled = new AtomicBoolean();
        @Override public long getDelay(TimeUnit unit) { return unit.convert(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS),
                other.getDelay(TimeUnit.NANOSECONDS)); }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            isCancelled.set(true);
            cancelled.countDown();
            return true;
        }
        @Override public boolean isCancelled() { return isCancelled.get(); }
        @Override public boolean isDone() { return isCancelled(); }
        @Override public Object get() throws InterruptedException, ExecutionException {
            cancelled.await();
            throw new CancellationException();
        }
        @Override public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (!cancelled.await(timeout, unit)) throw new TimeoutException();
            throw new CancellationException();
        }
    }

    static final class DuePageGate {
        private volatile long deadlineNanos = Long.MAX_VALUE;
        private final AtomicLong remainingPages = new AtomicLong(-1);
        void openUntil(long deadline) { remainingPages.set(-1); deadlineNanos = deadline; }
        void openOnePage(long deadline) { remainingPages.set(1); deadlineNanos = deadline; }
        void close() { deadlineNanos = 0; }
        boolean allowNextPage() {
            long pages = remainingPages.get();
            return System.nanoTime() < deadlineNanos
                    && (pages == -1 || pages > 0 && remainingPages.compareAndSet(pages, pages - 1));
        }
    }

    private record Snapshot(String arm, int cutoffSeconds, int targetDue, long issued, long duplicates,
                            long elapsedSeconds, long sqlMillis, List<String> candidateStates,
                            List<String> dispatchStates, long outstandingCandidates,
                            long issuanceCompletionElapsedSeconds,
                            boolean schedulerReturnedBeforeCutoff) {
        Snapshot withIssuanceCompletion(long elapsed) {
            return new Snapshot(arm, cutoffSeconds, targetDue, issued, duplicates, elapsedSeconds,
                    sqlMillis, candidateStates, dispatchStates, outstandingCandidates,
                    elapsed, schedulerReturnedBeforeCutoff);
        }
        Snapshot withSchedulerReturnedBeforeCutoff(boolean returned) {
            return new Snapshot(arm, cutoffSeconds, targetDue, issued, duplicates, elapsedSeconds,
                    sqlMillis, candidateStates, dispatchStates, outstandingCandidates,
                    issuanceCompletionElapsedSeconds, returned);
        }
        String toJson(boolean postCutoff) {
            return "{\"arm\":\"" + arm + "\",\"cutoffSeconds\":" + cutoffSeconds
                    + ",\"targetDue\":" + targetDue + ",\"committedUserCoupons\":" + issued
                    + ",\"duplicateUserCouponPairs\":" + duplicates + ",\"elapsedSeconds\":" + elapsedSeconds
                    + ",\"sqlQueryMillis\":" + sqlMillis + ",\"candidateStates\":\"" + candidateStates
                    + "\",\"dispatchStates\":\"" + dispatchStates + "\",\"targetFullyIssued\":"
                    + (issued >= targetDue) + ",\"issuanceCompletionElapsedSeconds\":"
                    + (issuanceCompletionElapsedSeconds >= 0 ? issuanceCompletionElapsedSeconds : "null")
                    + ",\"outstandingCandidates\":" + outstandingCandidates
                    + ",\"schedulerReturnedAtSnapshot\":" + schedulerReturnedBeforeCutoff
                    + ",\"postCutoffDrain\":" + postCutoff + "}\n";
        }
        String toMarkdown() {
            return "# Abandoned-cart issuance " + arm + "\n\n- Target due users: " + targetDue
                    + "\n- Committed target UserCoupon rows: " + issued + "\n- Duplicate pairs: " + duplicates
                    + "\n- Elapsed seconds: " + elapsedSeconds + "\n- Separate SQL lookup: " + sqlMillis + " ms"
                    + "\n- Candidate states: " + candidateStates + "\n- Dispatch states: " + dispatchStates
                    + "\n- Outstanding candidates: " + outstandingCandidates
                    + "\n- Target fully issued: " + (issued >= targetDue)
                    + "\n- Issuance completion observed at: " + issuanceCompletionElapsedSeconds + "s"
                    + "\n- Scheduler returned at snapshot: " + schedulerReturnedBeforeCutoff + "\n";
        }
    }
}
