package com.axon.entry_service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.entry_service.payment.v2.PaymentV2Facade;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentV2BootIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        mysql.start();
        redis.start();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        String migration = Files.readString(Path.of("../../scripts/migrations/2026-10-27-add-payment-state.sql"));
        for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        registry.add("axon.payment-v2.enabled", () -> "true");
        registry.add("axon.payment-v2.activity-ids", () -> "5");
        registry.add("axon.payment-v2.jdbc-url", mysql::getJdbcUrl);
        registry.add("axon.payment-v2.db-user", mysql::getUsername);
        registry.add("axon.payment-v2.db-password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @MockitoBean
    KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    DataSource dataSource;
    @Autowired
    PaymentV2Facade facade;

    @Test
    void enabledApplicationStartsWithExplicitDatabaseAndAllowlist() {
        assertThat(facade.handles(5)).isTrue();
        assertThat(facade.handles(6)).isFalse();
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM payment_reservations", Integer.class))
                .isZero();
    }
}
