package com.axon.core_service.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.axon.core_service.domain.dashboard.FunnelStep;
import com.axon.messaging.CampaignActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BehaviorEventServiceFunnelMappingTest {

    @Test
    @DisplayName("미구현 타입은 ES 조회 없이 0을 반환한다")
    void unsupportedTypeReturnsZeroWithoutElasticsearchQuery() throws Exception {
        ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
        BehaviorEventService service = new BehaviorEventService(elasticsearchClient);

        Long count = service.getFunnelStepCount(
                1L,
                CampaignActivityType.COUPON,
                FunnelStep.ENGAGE,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());

        assertThat(count).isZero();
        verifyNoInteractions(elasticsearchClient);
    }
}
