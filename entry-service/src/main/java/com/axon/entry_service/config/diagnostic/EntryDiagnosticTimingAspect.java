package com.axon.entry_service.config.diagnostic;

import com.axon.entry_service.service.entry.EntryUseCaseResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@Profile("diagnostic")
@RequiredArgsConstructor
public class EntryDiagnosticTimingAspect {

    private final MeterRegistry meterRegistry;

    @Value("${axon.diagnostic.entry.slow-threshold-ms:100}")
    private long slowThresholdMs;

    @Around("""
            execution(* com.axon.entry_service.service.entry.EntryApplicationService.createEntry(..)) ||
            execution(* com.axon.entry_service.service.CampaignActivityMetaService.getMeta(..)) ||
            execution(* com.axon.entry_service.service.payment.ReservationTokenService.generateDeterministicToken(..)) ||
            execution(* com.axon.entry_service.service.payment.ReservationTokenService.getPayloadFromToken(..)) ||
            execution(* com.axon.entry_service.service.payment.ReservationTokenService.issueToken(..)) ||
            execution(* com.axon.entry_service.service.FastValidationService.fastValidation(..)) ||
            execution(* com.axon.entry_service.service.CoreValidationService.isEligible(..)) ||
            execution(* com.axon.entry_service.service.EntryReservationService.reserve(..)) ||
            execution(* com.axon.entry_service.service.BackendEventPublisher.handleReservationApproved(..))
            """)
    public Object timeEntryStage(ProceedingJoinPoint joinPoint) throws Throwable {
        String stage = resolveStage(joinPoint);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            Object result = joinPoint.proceed();
            outcome = resolveOutcome(result);
            return result;
        } catch (Throwable throwable) {
            outcome = "exception";
            throw throwable;
        } finally {
            long elapsedNanos = System.nanoTime() - start;
            record(stage, outcome, elapsedNanos);
        }
    }

    private void record(String stage, String outcome, long elapsedNanos) {
        Timer.builder("axon.entry.diagnostic.stage")
                .description("Diagnostic-only Entry 200 path stage timing")
                .tag("stage", stage)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMs >= slowThresholdMs) {
            log.info("entry_diagnostic_stage stage={} outcome={} elapsedMs={}", stage, outcome, elapsedMs);
        }
    }

    private String resolveStage(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        return switch (className + "." + methodName) {
            case "EntryApplicationService.createEntry" -> "entry_total";
            case "CampaignActivityMetaService.getMeta" -> "meta_lookup";
            case "ReservationTokenService.generateDeterministicToken" -> "token_generate";
            case "ReservationTokenService.getPayloadFromToken" -> "token_lookup";
            case "ReservationTokenService.issueToken" -> "token_issue";
            case "FastValidationService.fastValidation" -> "fast_validation";
            case "CoreValidationService.isEligible" -> "heavy_validation";
            case "EntryReservationService.reserve" -> "redis_reserve";
            case "BackendEventPublisher.handleReservationApproved" -> "backend_event_publish_async";
            default -> className + "." + methodName;
        };
    }

    private String resolveOutcome(Object result) {
        if (result instanceof EntryUseCaseResult entryResult) {
            return entryResult.status().name();
        }
        return "success";
    }
}
