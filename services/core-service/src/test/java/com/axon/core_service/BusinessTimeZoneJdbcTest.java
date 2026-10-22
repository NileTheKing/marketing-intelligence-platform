package com.axon.core_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real FCFS purchases were stored 9h ahead: Purchase keeps KST wall-clock time, Hibernate binds it as
 * Timestamp.valueOf (JVM zone), and serverTimezone=Asia/Seoul converts from the JVM zone a second time.
 */
@Testcontainers(disabledWithoutDocker = true)
class BusinessTimeZoneJdbcTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    // 2026-10-19 17:10 KST
    private static final Instant PURCHASED_AT = Instant.parse("2026-10-19T08:10:00Z");

    private TimeZone originalZone;

    @BeforeEach
    void runLikeTheUtcContainer() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    void utcJvmStoresKstPurchaseTimeNineHoursAhead() throws Exception {
        assertThat(storePurchaseAt()).isEqualTo("2026-10-20 02:10:00");
    }

    @Test
    void businessTimeZoneStoresActualKstPurchaseTime() throws Exception {
        CoreServiceApplication.useBusinessTimeZone();

        assertThat(storePurchaseAt()).isEqualTo("2026-10-19 17:10:00");
    }

    private String storePurchaseAt() throws Exception {
        Purchase purchase = new Purchase(1L, 1L, 1L, PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.ONE, 1, PURCHASED_AT);
        String url = mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                + "serverTimezone=Asia/Seoul";

        try (Connection connection = DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE purchase_time (purchase_at DATETIME(6) NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO purchase_time (purchase_at) VALUES (?)")) {
                // Same binding Hibernate 6 uses for a LocalDateTime column.
                insert.setTimestamp(1, Timestamp.valueOf(purchase.getPurchaseAt()));
                insert.executeUpdate();
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT DATE_FORMAT(purchase_at, '%Y-%m-%d %H:%i:%s') FROM purchase_time")) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
