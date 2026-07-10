package com.axon.core_service.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class CorePipelineMetricsTest {

    @Test
    void recordsPipelineAndReconciliationMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CorePipelineMetrics metrics = new CorePipelineMetrics(registry);

        metrics.recordCommandFlush(20, () -> {});
        metrics.recordPurchaseFlush(10, () -> {});
        metrics.recordPurchaseIndividualRetry(2);
        metrics.recordDltRouted("purchase", 1);
        metrics.recordReconciliationResult(3);

        assertThat(registry.find("axon.pipeline.flush").tag("pipeline", "campaign-command").timer().count())
                .isEqualTo(1);
        assertThat(registry.find("axon.pipeline.flush.batch.size").tag("pipeline", "purchase").summary().totalAmount())
                .isEqualTo(10.0);
        assertThat(registry.find("axon.pipeline.retry.individual").tag("pipeline", "purchase").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.find("axon.pipeline.dlt.routed").tag("source", "purchase").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("axon.reconciliation.mismatch.count").gauge().value())
                .isEqualTo(3.0);
    }
}
