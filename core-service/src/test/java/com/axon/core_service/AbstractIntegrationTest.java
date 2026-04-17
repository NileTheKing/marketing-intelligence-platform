package com.axon.core_service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.redis.testcontainers.RedisContainer;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {


    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("axon_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        String redisHost = redis.getHost();
        Integer redisPort = redis.getFirstMappedPort();
        String mysqlUrl = mysql.getJdbcUrl();
        String kafkaServers = kafka.getBootstrapServers();

        // [DIAGNOSTIC LOG]
        System.out.println(">>> [DEBUG] Testcontainers Starting...");
        System.out.println(">>> [DEBUG] Redis Container: " + redisHost + ":" + redisPort);
        System.out.println(">>> [DEBUG] MySQL Container JDBC URL: " + mysqlUrl);
        System.out.println(">>> [DEBUG] Kafka Container Servers: " + kafkaServers);

        // Keep System properties for Redis/Redisson as they sometimes fail to pick up dynamic properties correctly
        System.setProperty("spring.data.redis.host", redisHost);
        System.setProperty("spring.data.redis.port", String.valueOf(redisPort));
        System.setProperty("spring.data.redis.password", ""); 

        registry.add("spring.data.redis.host", () -> redisHost);
        registry.add("spring.data.redis.port", () -> redisPort);
        registry.add("spring.data.redis.password", () -> "");

        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
