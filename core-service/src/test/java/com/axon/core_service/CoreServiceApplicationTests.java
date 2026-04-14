package com.axon.core_service;

import com.axon.core_service.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(TestSecurityConfig.class)
class CoreServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
