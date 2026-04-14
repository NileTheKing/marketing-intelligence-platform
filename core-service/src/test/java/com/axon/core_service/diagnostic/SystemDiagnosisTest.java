package com.axon.core_service.diagnostic;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.TestcontainersConfiguration;

public class SystemDiagnosisTest {

    @Test
    void diagnose() {
        System.out.println("\n===== [Testcontainers Big Picture Diagnosis - Part 2] =====");

        System.out.println("\n1. Environment Overrides (OS Env):");
        System.out.println("DOCKER_HOST: " + System.getenv("DOCKER_HOST"));
        System.out.println("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: " + System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"));

        System.out.println("\n2. JVM System Properties:");
        System.out.println("DOCKER_HOST: " + System.getProperty("DOCKER_HOST"));
        System.out.println("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: " + System.getProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"));

        System.out.println("\n3. Runtime Resolution:");
        try {
            System.out.println("Effective Docker Host: " + DockerClientFactory.instance().getTransportConfig().getDockerHost());
        } catch (Exception e) {
            System.out.println("Resolution Error: " + e.getMessage());
        }

        System.out.println("\n==========================================================\n");
    }
}
