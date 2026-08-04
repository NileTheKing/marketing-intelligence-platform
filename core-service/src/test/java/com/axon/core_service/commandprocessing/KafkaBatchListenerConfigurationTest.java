package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"test", "oauth"})
class KafkaBatchListenerConfigurationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Test
    @DisplayName("Kafka poll batch는 DB 처리 완료 후 BATCH ack 경계로 반환한다")
    void listenerUsesKafkaBatchAndExplicitCommitBoundary() {
        var containers = kafkaListenerEndpointRegistry.getListenerContainers();

        assertThat(containers).hasSize(1);
        var container = containers.iterator().next();
        assertThat(container.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(container.getContainerProperties().getMessageListener())
                .isInstanceOf(BatchMessageListener.class);
        assertThat(consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).hasToString("false");
        assertThat(consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).hasToString("20");
    }
}
