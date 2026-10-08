package com.axon.entry_service.config.diagnostic;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("diagnostic")
@RequiredArgsConstructor
public class EntryDiagnosticStageRecorder {

    private final MeterRegistry meterRegistry;

    public void record(String stage, String outcome, long elapsedNanos) {
        Timer.builder("axon.entry.diagnostic.stage")
                .description("Diagnostic-only Entry reservation stage timing")
                .tag("stage", stage)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
