package com.axon.entry_service.payment.v2;

import com.axon.entry_service.reservation.v2.RedisReservationHolds;
import com.axon.entry_service.reservation.v2.ReservationLifecycle;
import com.axon.payment.MockPaymentGateway;
import com.axon.payment.PaymentOutboxTransactions;
import com.axon.payment.PaymentTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "axon.payment-v2", name = "enabled", havingValue = "true")
@EnableScheduling
public class PaymentV2Configuration {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("payment-v2-worker-");
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource paymentDataSource(@Value("${axon.payment-v2.jdbc-url}") String url,
            @Value("${axon.payment-v2.db-user}") String user,
            @Value("${axon.payment-v2.db-password}") String password,
            @Value("${axon.payment-v2.pool-size:8}") int poolSize,
            @Value("${axon.payment-v2.register-mbeans:false}") boolean registerMbeans) {
        if (url.isBlank() || user.isBlank()) {
            throw new IllegalStateException("payment v2 requires an explicit JDBC URL and DB user");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("payment-v2");
        config.setRegisterMbeans(registerMbeans);
        return new HikariDataSource(config);
    }

    @Bean
    PaymentTransactions paymentTransactions(DataSource paymentDataSource) {
        return new PaymentTransactions(new JdbcTemplate(paymentDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(paymentDataSource)));
    }

    @Bean
    PaymentOutboxTransactions paymentOutboxTransactions(DataSource paymentDataSource) {
        return new PaymentOutboxTransactions(new JdbcTemplate(paymentDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(paymentDataSource)));
    }

    @Bean
    RedisReservationHolds redisReservationHolds(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisReservationHolds(redis, mapper);
    }

    @Bean
    ReservationLifecycle reservationLifecycle(RedisReservationHolds holds, PaymentTransactions payments) {
        return new ReservationLifecycle(holds, payments);
    }

    @Bean
    PaymentCredentials paymentCredentials(StringRedisTemplate redis, @Value("${payment.token.secret}") String secret) {
        return new PaymentCredentials(redis, secret);
    }

    @Bean
    PaymentConfirmation paymentConfirmation(PaymentTransactions payments, ObjectMapper mapper) {
        return new PaymentConfirmation(payments, new MockPaymentGateway(), mapper);
    }

    @Bean
    PaymentOutboxPublisher paymentOutboxPublisher(PaymentOutboxTransactions outbox,
            KafkaTemplate<String, Object> kafka, ObjectMapper mapper) {
        return new PaymentOutboxPublisher(outbox, new KafkaPaymentSender(kafka, mapper, Duration.ofSeconds(5)));
    }

    @Bean
    PaymentV2Facade paymentV2Facade(ReservationLifecycle lifecycle, PaymentTransactions payments,
            PaymentCredentials credentials, PaymentConfirmation confirmation,
            @Value("${axon.payment-v2.activity-ids:}") String activityIds, ApplicationEventPublisher events) {
        return new PaymentV2Facade(lifecycle, payments, credentials, confirmation, activityIds, events);
    }

    @Bean
    PaymentV2Worker paymentV2Worker(ReservationLifecycle lifecycle, PaymentConfirmation confirmation,
            PaymentOutboxPublisher publisher) {
        return new PaymentV2Worker(lifecycle, confirmation, publisher);
    }
}
