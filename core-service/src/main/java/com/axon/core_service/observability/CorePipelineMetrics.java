package com.axon.core_service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class CorePipelineMetrics {

    private final Timer commandFlushTimer;
    private final Timer purchaseFlushTimer;
    private final DistributionSummary commandFlushBatchSize;
    private final DistributionSummary purchaseFlushBatchSize;
    private final Counter purchaseIndividualRetry;
    private final AtomicInteger reconciliationMismatchCount = new AtomicInteger();
    private final MeterRegistry meterRegistry;

    public CorePipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.commandFlushTimer = flushTimer("campaign-command");
        this.purchaseFlushTimer = flushTimer("purchase");
        this.commandFlushBatchSize = flushBatchSize("campaign-command");
        this.purchaseFlushBatchSize = flushBatchSize("purchase");
        this.purchaseIndividualRetry = Counter.builder("axon.pipeline.retry.individual")
                .tag("pipeline", "purchase")
                .register(meterRegistry);

        Gauge.builder("axon.reconciliation.mismatch.count", reconciliationMismatchCount, AtomicInteger::get)
                .description("Mismatch count found by the most recent reconciliation run")
                .register(meterRegistry);
    }

    public void recordCommandFlush(int batchSize, Runnable action) {
        commandFlushBatchSize.record(batchSize);
        commandFlushTimer.record(action);
    }

    public void recordPurchaseFlush(int batchSize, Runnable action) {
        purchaseFlushBatchSize.record(batchSize);
        purchaseFlushTimer.record(action);
    }

    public void recordPurchaseIndividualRetry(int count) {
        purchaseIndividualRetry.increment(count);
    }

    public void recordDltRouted(String source, int count) {
        Counter.builder("axon.pipeline.dlt.routed")
                .tag("source", source)
                .register(meterRegistry)
                .increment(count);
    }

    public void recordReconciliationResult(int mismatchCount) {
        reconciliationMismatchCount.set(mismatchCount);
        Counter.builder("axon.reconciliation.run")
                .tag("outcome", mismatchCount == 0 ? "clean" : "mismatch")
                .register(meterRegistry)
                .increment();
    }

    public void recordReconciliationFailure() {
        Counter.builder("axon.reconciliation.run")
                .tag("outcome", "failure")
                .register(meterRegistry)
                .increment();
    }

    private Timer flushTimer(String pipeline) {
        return Timer.builder("axon.pipeline.flush")
                .tag("pipeline", pipeline)
                .register(meterRegistry);
    }

    private DistributionSummary flushBatchSize(String pipeline) {
        return DistributionSummary.builder("axon.pipeline.flush.batch.size")
                .tag("pipeline", pipeline)
                .register(meterRegistry);
    }
}
