package com.axon.entry_service.reservation.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.axon.payment.PaymentTransactions;
import com.axon.entry_service.payment.CheckoutTokenService;
import com.axon.entry_service.payment.PaymentController;
import com.axon.entry_service.payment.PaymentService;
import com.axon.entry_service.payment.v2.PaymentCredentials;
import com.axon.entry_service.payment.v2.PaymentConfirmation;
import com.axon.entry_service.payment.v2.PaymentV2Facade;
import com.axon.entry_service.payment.v2.PaymentV2Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import javax.sql.DataSource;
import com.axon.entry_service.reservation.CampaignActivityMetaService;
import com.axon.entry_service.reservation.CoreValidationService;
import com.axon.entry_service.reservation.EntryApplicationService;
import com.axon.entry_service.reservation.EntryController;
import com.axon.entry_service.reservation.EntryReservationService;
import com.axon.entry_service.reservation.FastValidationService;
import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.CampaignActivityStatus;
import com.axon.messaging.CampaignActivityType;
import com.axon.payment.MockPaymentGateway;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.axon.payment.PaymentTransactions.Reservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReservationLifecycleIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private JdbcTemplate jdbc;
    private PaymentTransactions payments;
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redisTemplate;
    private RedisReservationHolds holds;
    private ReservationLifecycle lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS payment_outbox");
        jdbc.execute("DROP TABLE IF EXISTS payments");
        jdbc.execute("DROP TABLE IF EXISTS payment_reservations");
        String migration = Files.readString(Path.of("../../scripts/migrations/2026-10-27-add-payment-state.sql"));
        for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        payments = new PaymentTransactions(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        connections = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connections.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connections);
        redisTemplate.afterPropertiesSet();
        holds = new RedisReservationHolds(redisTemplate, new ObjectMapper().findAndRegisterModules());
        lifecycle = new ReservationLifecycle(holds, payments);
    }

    @AfterEach
    void tearDown() {
        try (var connection = connections.getConnection()) {
            connection.serverCommands().flushAll();
        }
        connections.destroy();
        SecurityContextHolder.clearContext();
    }

    private Instant dbNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
    }

    private Reservation hold(long userId, Instant deadline) {
        return new Reservation(UUID.randomUUID(), userId, 5, 7, 1, deadline,
                new BigDecimal("12.00"), new BigDecimal("12.00"), "KRW");
    }

    @Test
    void featureOffNeedsNoDatabaseAndOnBuildsConditionalComponents() {
        new ApplicationContextRunner().withUserConfiguration(PaymentV2Configuration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(PaymentV2Facade.class);
        });
        new ApplicationContextRunner().withUserConfiguration(PaymentV2Configuration.class)
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withPropertyValues("axon.payment-v2.enabled=true", "axon.payment-v2.jdbc-url=" + mysql.getJdbcUrl(),
                        "axon.payment-v2.db-user=" + mysql.getUsername(),
                        "axon.payment-v2.db-password=" + mysql.getPassword(),
                        "payment.token.secret=test-secret-that-is-at-least-32-bytes",
                        "axon.payment-v2.worker-interval-ms=60000", "axon.payment-v2.outbox-interval-ms=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).hasSingleBean(PaymentV2Facade.class);
                    assertThat(context.getBean(PaymentV2Facade.class).handles(5L)).isFalse();
                });
    }

    @Test
    void legacyActivityStateRefusesV2AdmissionWithoutMutation() {
        redisTemplate.opsForValue().set("campaign:5:counter", "0");
        Reservation proposed = hold(51, dbNow().plusSeconds(60));
        assertThatThrownBy(() -> holds.admit(proposed, 10)).isInstanceOf(RuntimeException.class);
        assertThat(holds.find(proposed.id())).isNull();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isNull();
    }

    @Test
    void authenticatedHttpAdmissionPrepareConfirmAndReplayUseSignedStageTokens() throws Exception {
        var metadata = mock(CampaignActivityMetaService.class);
        var meta = new CampaignActivityMeta(5L, 1L, 3, CampaignActivityStatus.ACTIVE, null, null,
                List.of(), false, false, 7L, null, CampaignActivityType.FIRST_COME_FIRST_SERVE, null,
                new BigDecimal("12.34"), "KRW");
        when(metadata.getMeta(5L)).thenReturn(meta);
        when(metadata.getPaymentMeta(5L)).thenReturn(meta);
        var events = mock(ApplicationEventPublisher.class);
        var credentials = new PaymentCredentials(redisTemplate, "test-secret-that-is-at-least-32-bytes");
        var facade = new PaymentV2Facade(lifecycle, payments, credentials,
                new PaymentConfirmation(payments, new MockPaymentGateway(), new ObjectMapper()), "5", events);
        var entry = new EntryApplicationService(mock(EntryReservationService.class), metadata,
                mock(CoreValidationService.class), mock(FastValidationService.class), mock(CheckoutTokenService.class));
        ReflectionTestUtils.setField(entry, "paymentV2", facade);
        var payment = new PaymentController(mock(CheckoutTokenService.class), mock(PaymentService.class));
        ReflectionTestUtils.setField(payment, "paymentV2", facade);
        var mvc = MockMvcBuilders.standaloneSetup(new EntryController(entry), payment)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                User.withUsername("42").password("unused").authorities("ROLE_USER").build(), "unused"));
        String entryJson = mvc.perform(post("/api/v1/entries").header("Authorization", "Bearer test")
                        .contentType("application/json").content("{\"campaignActivityId\":5,\"productId\":7,\"quantity\":2}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String primary = new ObjectMapper().readTree(entryJson).get("reservationToken").asText();
        verify(events).publishEvent(any(com.axon.entry_service.reservation.event.ReservationApprovedEvent.class));
        String tampered = primary.substring(0, primary.length() - 1) + (primary.endsWith("0") ? "1" : "0");
        mvc.perform(post("/api/v1/payments/prepare").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", tampered))))
                .andExpect(status().isGone());
        mvc.perform(post("/api/v1/payments/confirm").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", primary))))
                .andExpect(status().isGone());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                User.withUsername("43").password("unused").authorities("ROLE_USER").build(), "unused"));
        mvc.perform(post("/api/v1/payments/prepare").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", primary))))
                .andExpect(status().isForbidden());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                User.withUsername("42").password("unused").authorities("ROLE_USER").build(), "unused"));
        String prepared = mvc.perform(post("/api/v1/payments/prepare").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", primary))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String approval = new ObjectMapper().readTree(prepared).get("approvalToken").asText();
        mvc.perform(post("/api/v1/payments/confirm").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", approval))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/confirm").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", approval))))
                .andExpect(status().isOk());
        String preparedAgain = mvc.perform(post("/api/v1/payments/prepare").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", primary))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondApproval = new ObjectMapper().readTree(preparedAgain).get("approvalToken").asText();
        mvc.perform(post("/api/v1/payments/confirm").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(Map.of("reservationToken", secondApproval))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentAdmissionEnforcesCapacityAndDuplicateReturnsOriginalHold() throws Exception {
        Instant deadline = dbNow().plusSeconds(60);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Reservation>>();
            for (int i = 0; i < 20; i++) {
                Reservation proposed = hold(i + 1, deadline);
                tasks.add(pool.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start timeout");
                    }
                    return holds.admit(proposed, 5);
                }));
            }
            start.countDown();
            List<Reservation> admitted = new ArrayList<>();
            for (var task : tasks) {
                Reservation result = task.get(10, TimeUnit.SECONDS);
                if (result != null) {
                    admitted.add(result);
                }
            }
            assertThat(admitted).hasSize(5);
            Reservation first = admitted.getFirst();
            assertThat(holds.admit(hold(first.userId(), deadline), 5)).isEqualTo(first);
            assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("5");
            assertThat(redisTemplate.opsForSet().size("axon:v2:activity:5:users")).isEqualTo(5);
            assertThat(redisTemplate.getExpire("axon:v2:holds")).isEqualTo(-1);
            assertThat(redisTemplate.getExpire("axon:v2:unregistered")).isEqualTo(-1);
        } finally {
            start.countDown();
        }
    }

    @Test
    void orphanIsFencedBeforeReleaseAndLateRegistrationCannotResurrect() {
        Reservation orphan = hold(101, dbNow().minusSeconds(1));
        assertThat(holds.admit(orphan, 1)).isEqualTo(orphan);
        lifecycle.recoverOrphans(10);
        assertThat(payments.status(orphan.id()).reservationState()).isEqualTo("EXPIRED");
        assertThat(payments.status(orphan.id()).releasePending()).isFalse();
        assertThat(payments.register(orphan).reservationState()).isEqualTo("EXPIRED");
        assertThat(holds.find(orphan.id())).isNull();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("0");
        assertThatThrownBy(() -> holds.admit(orphan, 1)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void committedRegistrationWithLostResponseReusesOriginalHold() {
        Reservation original = hold(151, dbNow().plusSeconds(60));
        PaymentTransactions lostCommitResponse = spy(payments);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("lost DB commit response");
        }).when(lostCommitResponse).register(any());
        ReservationLifecycle interrupted = new ReservationLifecycle(holds, lostCommitResponse);

        assertThatThrownBy(() -> interrupted.admitAndRegister(original, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(holds.isUnregistered(original.id())).isTrue();
        assertThat(payments.status(original.id()).reservationState()).isEqualTo("RESERVED");
        var retried = lifecycle.admitAndRegister(hold(151, dbNow().plusSeconds(90)), 1);
        assertThat(retried.reservation()).isEqualTo(original);
        assertThat(holds.isUnregistered(original.id())).isFalse();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("1");
    }

    @Test
    void registrationFailureLeavesHoldForTombstoneRecovery() {
        Reservation original = hold(152, dbNow().minusSeconds(1));
        PaymentTransactions unavailable = spy(payments);
        doThrow(new IllegalStateException("DB unavailable")).when(unavailable).register(any());
        ReservationLifecycle interrupted = new ReservationLifecycle(holds, unavailable);

        assertThatThrownBy(() -> interrupted.admitAndRegister(original, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(payments.status(original.id())).isNull();
        assertThat(holds.isUnregistered(original.id())).isTrue();
        lifecycle.recoverOrphans(10);
        assertThat(payments.status(original.id()).reservationState()).isEqualTo("EXPIRED");
        assertThat(payments.status(original.id()).releasePending()).isFalse();
        assertThat(holds.find(original.id())).isNull();
    }

    @Test
    void lifecycleRejectsOuterTransactionsBeforeTouchingRedis() {
        Reservation original = hold(153, dbNow().plusSeconds(60));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> lifecycle.admitAndRegister(original, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(holds.find(original.id())).isNull();
    }

    @Test
    void registeredProcessingAndCompletedNeverExpire() throws Exception {
        Instant deadline = dbNow().plusSeconds(2);
        Reservation processing = hold(201, deadline);
        Reservation completed = hold(202, deadline);
        lifecycle.admitAndRegister(processing, 2);
        lifecycle.admitAndRegister(completed, 2);
        var processingClaim = payments.claim(processing.id());
        var completedClaim = payments.claim(completed.id());
        assertThat(processingClaim).isNotNull();
        payments.complete(completedClaim, UUID.randomUUID(), "approval", "{}");
        // Simulate a lost index cleanup after DB registration.
        redisTemplate.opsForZSet().add("axon:v2:unregistered", processing.id().toString(), deadline.toEpochMilli());
        redisTemplate.opsForZSet().add("axon:v2:unregistered", completed.id().toString(), deadline.toEpochMilli());
        awaitDeadline(deadline);
        lifecycle.expireDue(10);
        lifecycle.recoverOrphans(10);
        assertThat(payments.status(processing.id()).reservationState()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(payments.status(completed.id()).reservationState()).isEqualTo("COMPLETED");
        assertThat(holds.isUnregistered(processing.id())).isFalse();
        assertThat(holds.isUnregistered(completed.id())).isFalse();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("2");
    }

    @Test
    void redisFailureAndLostReleaseAcknowledgementAreRetriedWithoutDoubleDecrement() throws Exception {
        Reservation first = hold(301, dbNow().plusSeconds(2));
        lifecycle.admitAndRegister(first, 1);
        awaitDeadline(first.deadline());
        RedisReservationHolds failing = spy(holds);
        doThrow(new IllegalStateException("Redis unavailable")).when(failing).release(any());
        ReservationLifecycle unavailable = new ReservationLifecycle(failing, payments);
        assertThatThrownBy(() -> unavailable.expireDue(10)).isInstanceOf(IllegalStateException.class);
        assertThat(payments.status(first.id()).releasePending()).isTrue();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("1");

        RedisReservationHolds lostAck = spy(holds);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("lost Redis response");
        }).when(lostAck).release(any());
        ReservationLifecycle interrupted = new ReservationLifecycle(lostAck, payments);
        assertThatThrownBy(() -> interrupted.retryReleases(10)).isInstanceOf(IllegalStateException.class);
        assertThat(payments.status(first.id()).releasePending()).isTrue();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("0");
        lifecycle.retryReleases(10);
        lifecycle.retryReleases(10);
        assertThat(payments.status(first.id()).releasePending()).isFalse();
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("0");
    }

    @Test
    void lateReleaseDoesNotTouchNewOwner() throws Exception {
        Reservation old = hold(401, dbNow().plusSeconds(2));
        lifecycle.admitAndRegister(old, 1);
        awaitDeadline(old.deadline());
        lifecycle.expireDue(10);
        Reservation newer = hold(401, dbNow().plusSeconds(60));
        lifecycle.admitAndRegister(newer, 1);
        holds.release(old);
        assertThat(holds.find(newer.id())).isEqualTo(newer);
        assertThat(redisTemplate.opsForValue().get("axon:v2:activity:5:counter")).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().isMember("axon:v2:activity:5:users", "401")).isTrue();
    }

    private void awaitDeadline(Instant deadline) throws InterruptedException {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dbNow().isBefore(deadline.plusMillis(10))) {
            if (System.nanoTime() >= timeout) {
                throw new IllegalStateException("deadline wait timed out");
            }
            Thread.sleep(25);
        }
    }
}
