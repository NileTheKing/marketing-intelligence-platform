package com.axon.core_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@EnableAsync
@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
public class CoreServiceApplication {

	public static void main(String[] args) {
		useBusinessTimeZone();
		SpringApplication.run(CoreServiceApplication.class, args);
	}

	/**
	 * LocalDateTime columns hold Asia/Seoul wall-clock time, and the JDBC URL's serverTimezone=Asia/Seoul
	 * converts each written timestamp from the JVM zone. On a UTC container JVM that conversion shifted
	 * every stored time by +9h, and LocalDateTime.now() lagged KST by 9h.
	 */
	static void useBusinessTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

}

