package com.axon.core_service.observability;

import com.axon.core_service.domain.reconciliation.ReconciliationIssueType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
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
    private final EnumMap<ReconciliationIssueType, AtomicInteger> openReconciliationIssueCounts =
            new EnumMap<>(ReconciliationIssueType.class);
    private final Timer reconciliationScanTimer;
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
        this.reconciliationScanTimer = Timer.builder("axon.reconciliation.scan")
                .description("Duration of a reconciliation scan")
                .register(meterRegistry);

        Gauge.builder("axon.reconciliation.mismatch.count", reconciliationMismatchCount, AtomicInteger::get)
                .description("Mismatch count found by the most recent reconciliation run")
                .register(meterRegistry);

        for (ReconciliationIssueType issueType : ReconciliationIssueType.values()) {
            AtomicInteger count = new AtomicInteger();
            openReconciliationIssueCounts.put(issueType, count);
            Gauge.builder("axon.reconciliation.issue.open", count, AtomicInteger::get)
                    .tag("type", issueType.name())
                    .description("Open reconciliation issue count by type")
                    .register(meterRegistry);
        }
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

    public void recordReconciliationScan(Runnable action) {
        reconciliationScanTimer.record(action);
    }

    public void recordReconciliationIssueDetection(ReconciliationIssueType issueType,
                                                   boolean newOccurrence, long ageSeconds) {
        if (newOccurrence) {
            Counter.builder("axon.reconciliation.issue.detected")
                    .tag("type", issueType.name())
                    .register(meterRegistry)
                    .increment();
        }
        DistributionSummary.builder("axon.reconciliation.issue.age.seconds")
                .tag("type", issueType.name())
                .register(meterRegistry)
                .record(ageSeconds);
    }

    public void setOpenReconciliationIssueCount(ReconciliationIssueType issueType, long count) {
        openReconciliationIssueCounts.get(issueType).set(Math.toIntExact(count));
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
