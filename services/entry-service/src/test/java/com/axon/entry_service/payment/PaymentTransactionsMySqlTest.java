package com.axon.entry_service.payment;

import com.axon.payment.PaymentTransactions;
import com.axon.payment.PaymentTransactions.Reservation;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PaymentTransactionsMySqlTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    JdbcTemplate jdbc;
    PaymentTransactions service;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("DROP TABLE IF EXISTS payment_outbox"); jdbc.execute("DROP TABLE IF EXISTS payments"); jdbc.execute("DROP TABLE IF EXISTS payment_reservations");
        for (String statement : Files.readString(Path.of("../../scripts/migrations/2026-10-27-add-payment-state.sql")).split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        service = new PaymentTransactions(jdbc, tx);
    }
    Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant(); }
    Reservation reservation(Instant deadline) { return new Reservation(UUID.randomUUID(),1,2,3,2,deadline,new BigDecimal("4.50"),new BigDecimal("9.00"),"KRW"); }
    Reservation altered(Reservation r) { return new Reservation(r.id(),1,2,4,2,r.deadline(),r.unitPrice(),r.totalAmount(),r.currency()); }

    @Test void tombstoneAndRegistrationRaceAndIdentityValidation() throws Exception {
        var r=reservation(now().minusSeconds(1).plusNanos(123));
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return service.register(r);});
            var b=pool.submit(()->{start.await();return service.expireOrFence(r);});
            start.countDown();
            assertThat(a.get().reservationState()).isEqualTo("EXPIRED");
            assertThat(b.get().reservationState()).isEqualTo("EXPIRED");
        }
        assertThat(service.register(r).releasePending()).isTrue();
        assertThatThrownBy(()->service.register(altered(r))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.expireOrFence(altered(r))).isInstanceOf(IllegalStateException.class);
        service.acknowledgeRelease(r.id()); service.acknowledgeRelease(r.id());
        assertThat(service.status(r.id()).releasePending()).isFalse();
    }
    @Test void replaySnapshotAndAtomicRollbackWithFencing() {
        var r=reservation(now().plusSeconds(60)); service.register(r);
        assertThat(service.register(r).reservationState()).isEqualTo("RESERVED");
        var old=service.claim(r.id());
        assertThat(service.register(r).reservationState()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(service.takeover(old.paymentId(), Duration.ofHours(1))).isNull();
        jdbc.update("UPDATE payments SET claimed_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 SECOND)");
        var current=service.takeover(old.paymentId(),Duration.ofSeconds(1));
        assertThat(service.complete(old,UUID.randomUUID(),"old","{}")).isEqualTo(PaymentTransactions.Completion.NOT_OWNER);
        var event=UUID.randomUUID();
        jdbc.update("INSERT INTO payment_outbox (event_id,payment_id,payload,state) VALUES (?,?,'{}','PENDING')",event.toString(),current.paymentId().toString());
        assertThatThrownBy(()->service.complete(current,event,"approval","{}"));
        assertThat(service.status(r.id()).reservationState()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(service.status(r.id()).payment().state()).isEqualTo("PROCESSING");
        jdbc.update("DELETE FROM payment_outbox");
        assertThat(service.complete(current,event,"approval","{}")).isEqualTo(PaymentTransactions.Completion.COMPLETED);
        assertThat(service.complete(current,event,"approval","{}")).isEqualTo(PaymentTransactions.Completion.ALREADY_COMPLETED);
        assertThatThrownBy(()->service.complete(current,event,"approval","different")).isInstanceOf(IllegalStateException.class);
        var result=service.register(r);
        assertThat(result.reservationState()).isEqualTo("COMPLETED");
        assertThat(result.reservation()).isEqualTo(r);
        assertThat(result.payment().paymentId()).isEqualTo(current.paymentId());
        assertThat(result.payment().eventId()).isEqualTo(event);
        assertThat(result.payment().approvalId()).isEqualTo("approval");
    }
    @Test void failedPaymentNeedsReleaseAndStaleOwnerCannotFail() {
        var r=reservation(now().plusSeconds(60)); service.register(r); var old=service.claim(r.id());
        assertThat(service.takeover(old.paymentId(), Duration.ofHours(1))).isNull();
        jdbc.update("UPDATE payments SET claimed_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 SECOND)");
        var current=service.takeover(old.paymentId(),Duration.ofSeconds(1));
        assertThat(service.fail(old)).isFalse(); assertThat(service.fail(current)).isTrue(); assertThat(service.fail(current)).isFalse();
        assertThat(service.status(r.id()).reservationState()).isEqualTo("FAILED");
        assertThat(service.status(r.id()).releasePending()).isTrue();
        service.acknowledgeRelease(r.id()); assertThat(service.status(r.id()).releasePending()).isFalse();
    }
    @Test void lockWaitPastDeadlineCannotConfirm() throws Exception {
        var r=reservation(now().plusSeconds(2)); service.register(r);
        var locked=new CountDownLatch(1); var unlock=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->tx.executeWithoutResult(s->{jdbc.queryForObject("SELECT state FROM payment_reservations WHERE reservation_id=? FOR UPDATE",String.class,r.id().toString()); locked.countDown(); try { if (!unlock.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var confirmation=pool.submit(()->service.claim(r.id()));
            while (now().isBefore(r.deadline().plusMillis(100))) Thread.sleep(50);
            unlock.countDown(); holder.get();
            assertThat(confirmation.get()).isNull();
            assertThat(service.status(r.id()).reservationState()).isEqualTo("EXPIRED");
            assertThat(service.status(r.id()).releasePending()).isTrue();
        } finally { unlock.countDown(); }
    }
    @Test void earlyFenceRollsBackAndCompletedIsNeverExpired() {
        var r=reservation(now().plusSeconds(60));
        assertThatThrownBy(()->service.expireOrFence(r)).isInstanceOf(IllegalStateException.class);
        assertThat(service.status(r.id())).isNull();
        service.register(r); var claim=service.claim(r.id()); service.complete(claim,UUID.randomUUID(),"ok","{}");
        assertThatThrownBy(()->service.expireOrFence(r)).isInstanceOf(IllegalStateException.class);
        assertThat(service.status(r.id()).reservationState()).isEqualTo("COMPLETED");
    }
}
