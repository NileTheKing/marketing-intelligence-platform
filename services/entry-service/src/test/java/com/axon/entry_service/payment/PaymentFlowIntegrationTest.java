package com.axon.entry_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.entry_service.payment.v2.KafkaPaymentSender;
import com.axon.entry_service.payment.v2.PaymentConfirmation;
import com.axon.entry_service.payment.v2.PaymentOutboxPublisher;
import com.axon.messaging.topic.KafkaTopics;
import com.axon.payment.MockPaymentGateway;
import com.axon.payment.PaymentGateway;
import com.axon.payment.PaymentOutboxTransactions;
import com.axon.payment.PaymentTransactions;
import com.axon.payment.PaymentTransactions.Reservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PaymentFlowIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

    private JdbcTemplate jdbc;
    private PaymentTransactions payments;
    private PaymentOutboxTransactions outbox;
    private TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

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
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        payments = new PaymentTransactions(jdbc, tx);
        outbox = new PaymentOutboxTransactions(jdbc, tx);
    }

    private Reservation reservation(long userId) {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
        return new Reservation(UUID.randomUUID(), userId, 5, 7, 2, now.plusSeconds(60),
                new BigDecimal("12.00"), new BigDecimal("24.00"), "KRW");
    }

    private void makeStale() {
        jdbc.update("UPDATE payments SET claimed_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 SECOND)");
    }

    private void expireClaims() {
        jdbc.update("UPDATE payment_outbox SET claimed_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE state = 'CLAIMED'");
    }

    @Test
    void confirmationReplaysStoredResultAndRunsGatewayOutsideTransaction() {
        var reservation = reservation(1);
        payments.register(reservation);
        var mock = new MockPaymentGateway();
        PaymentGateway gateway = new PaymentGateway() {
            public Result approve(UUID id, Reservation snapshot) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return mock.approve(id, snapshot);
            }
            public Result lookup(UUID id, Reservation snapshot) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new MockPaymentGateway().lookup(id, snapshot);
            }
        };
        var confirmation = new PaymentConfirmation(payments, gateway, mapper);
        assertThat(confirmation.confirm(reservation.id(), 999).outcome()).isEqualTo(PaymentConfirmation.Outcome.FORBIDDEN);
        var first = confirmation.confirm(reservation.id(), 1);
        var replay = confirmation.confirm(reservation.id(), 1);
        assertThat(first.outcome()).isEqualTo(PaymentConfirmation.Outcome.COMPLETED);
        assertThat(replay.payment()).isEqualTo(first.payment());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> confirmation.confirm(reservation.id(), 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutAfterApprovalUnknownThenLookupSuccessCompletesSamePayment() {
        var reservation = reservation(2);
        payments.register(reservation);
        PaymentGateway gateway = new PaymentGateway() {
            private boolean unknown = true;
            public Result approve(UUID id, Reservation snapshot) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                throw new IllegalStateException("timeout after approval");
            }
            public Result lookup(UUID id, Reservation snapshot) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (unknown) {
                    unknown = false;
                    return new Result(Outcome.UNKNOWN, null);
                }
                return new MockPaymentGateway().lookup(id, snapshot);
            }
        };
        var confirmation = new PaymentConfirmation(payments, gateway, mapper);
        var processing = confirmation.confirm(reservation.id(), 2);
        assertThat(processing.outcome()).isEqualTo(PaymentConfirmation.Outcome.PROCESSING);
        assertThat(confirmation.confirm(reservation.id(), 2).payment().paymentId()).isEqualTo(processing.payment().paymentId());
        makeStale();
        confirmation.recover(Duration.ofSeconds(1), 10);
        assertThat(payments.status(reservation.id()).reservationState()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(payments.status(reservation.id()).releasePending()).isFalse();
        makeStale();
        confirmation.recover(Duration.ofSeconds(1), 10);
        var recovered = confirmation.confirm(reservation.id(), 2);
        assertThat(recovered.outcome()).isEqualTo(PaymentConfirmation.Outcome.COMPLETED);
        assertThat(recovered.payment().paymentId()).isEqualTo(processing.payment().paymentId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void declineOnApprovalOrRecoverySchedulesRelease() {
        PaymentGateway declined = new PaymentGateway() {
            public Result approve(UUID id, Reservation snapshot) { return new Result(Outcome.DECLINED, null); }
            public Result lookup(UUID id, Reservation snapshot) { return new Result(Outcome.DECLINED, null); }
        };
        var first = reservation(3);
        payments.register(first);
        var confirmation = new PaymentConfirmation(payments, declined, mapper);
        assertThat(confirmation.confirm(first.id(), 3).outcome()).isEqualTo(PaymentConfirmation.Outcome.FAILED);
        assertThat(payments.status(first.id()).releasePending()).isTrue();
        var second = reservation(4);
        payments.register(second);
        payments.claim(second.id());
        makeStale();
        confirmation.recover(Duration.ofSeconds(1), 10);
        assertThat(payments.status(second.id()).reservationState()).isEqualTo("FAILED");
        assertThat(payments.status(second.id()).releasePending()).isTrue();
    }

    @Test
    void concurrentOutboxClaimsHaveDistinctOwnersAndExpiredOwnerCannotAcknowledge() throws Exception {
        var confirmation = new PaymentConfirmation(payments, new MockPaymentGateway(), mapper);
        for (int i = 0; i < 4; i++) {
            var reservation = reservation(10 + i);
            payments.register(reservation);
            confirmation.confirm(reservation.id(), reservation.userId());
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return outbox.claim(4, Duration.ofSeconds(30)); });
            var two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return outbox.claim(4, Duration.ofSeconds(30)); });
            start.countDown();
            var all = new ArrayList<>(one.get(10, TimeUnit.SECONDS));
            all.addAll(two.get(10, TimeUnit.SECONDS));
            assertThat(all).hasSize(4);
            assertThat(all.stream().map(PaymentOutboxTransactions.Delivery::eventId).distinct()).hasSize(4);
            var old = all.getFirst();
            expireClaims();
            var replacement = outbox.claim(4, Duration.ofSeconds(30)).stream()
                    .filter(item -> item.eventId().equals(old.eventId())).findFirst().orElseThrow();
            assertThat(outbox.delivered(old)).isFalse();
            assertThat(outbox.delivered(replacement)).isTrue();
        } finally {
            start.countDown();
        }
    }

    @Test
    void outboxClaimsOldestEventFirst() {
        var confirmation = new PaymentConfirmation(payments, new MockPaymentGateway(), mapper);
        var first = reservation(30);
        var second = reservation(31);
        payments.register(first);
        payments.register(second);
        confirmation.confirm(first.id(), first.userId());
        confirmation.confirm(second.id(), second.userId());
        UUID older = payments.status(second.id()).payment().eventId();
        jdbc.update("UPDATE payment_outbox SET created_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR) "
                + "WHERE event_id = ?", older.toString());
        assertThat(outbox.claim(1, Duration.ofSeconds(30)).getFirst().eventId()).isEqualTo(older);
    }

    @Test
    void brokerAcceptanceWithLostAckReplaysSameEventAndPayload() throws Exception {
        var reservation = reservation(20);
        payments.register(reservation);
        new PaymentConfirmation(payments, new MockPaymentGateway(), mapper).confirm(reservation.id(), 20);
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        var factory = new DefaultKafkaProducerFactory<String, Object>(config);
        var sender = new KafkaPaymentSender(new KafkaTemplate<>(factory), mapper, Duration.ofSeconds(10));
        Map<String, Object> consumerConfig = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "payment-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(consumerConfig)) {
            var interrupted = new PaymentOutboxPublisher(outbox, (eventId, payload) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                sender.sendAndAwaitAck(eventId, payload);
                throw new IllegalStateException("ack lost after broker acceptance");
            });
            assertThatThrownBy(() -> interrupted.publish(1, Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
            expireClaims();
            new PaymentOutboxPublisher(outbox, sender).publish(1, Duration.ofSeconds(30));
            consumer.subscribe(List.of(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND));
            var messages = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (messages.size() < 2 && System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(250)).forEach(messages::add);
            }
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).key()).isEqualTo(messages.get(1).key());
            assertThat(messages.get(0).value()).isEqualTo(messages.get(1).value());
            var payload = mapper.readTree(messages.getFirst().value());
            assertThat(payload.get("paymentId").asText()).isEqualTo(payments.status(reservation.id()).payment().paymentId().toString());
            assertThat(payload.get("eventId").asText()).isEqualTo(messages.getFirst().key());
            assertThat(payload.get("price").decimalValue()).isEqualByComparingTo("12.00");
            assertThat(jdbc.queryForObject("SELECT state FROM payment_outbox", String.class)).isEqualTo("DELIVERED");
        } finally {
            factory.destroy();
        }
    }
}
