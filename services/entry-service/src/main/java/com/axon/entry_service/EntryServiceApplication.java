package com.axon.entry_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class EntryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(EntryServiceApplication.class, args);
	}

}
