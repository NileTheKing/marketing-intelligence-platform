package com.axon.entry_service.reservation;

import com.axon.entry_service.config.diagnostic.EntryDiagnosticStageRecorder;
import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.ReservationResult;
import com.axon.entry_service.reservation.event.ReservationApprovedEvent;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EntryReservationService {

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private EntryDiagnosticStageRecorder diagnosticStageRecorder;
    private final RedisScript<Long> reservationScript =
            new DefaultRedisScript<>(RESERVATION_LUA, Long.class);
    private final RedisScript<Long> cancellationScript =
            new DefaultRedisScript<>(CANCELLATION_LUA, Long.class);

    @Autowired(required = false)
    void setDiagnosticStageRecorder(EntryDiagnosticStageRecorder diagnosticStageRecorder) {
        this.diagnosticStageRecorder = diagnosticStageRecorder;
    }

    private static final String RESERVATION_LUA = """
        local added = redis.call('SADD', KEYS[1], ARGV[1])
        if added == 0 then
            return -1
        end
        
        local count = redis.call('INCR', KEYS[2])
        
        if tonumber(ARGV[2]) > 0 and count > tonumber(ARGV[2]) then
            redis.call('SREM', KEYS[1], ARGV[1])
            redis.call('DECR', KEYS[2])
            return -2
        end
        
        return count
    """;

    private static final String CANCELLATION_LUA = """
        local removed = redis.call('SREM', KEYS[1], ARGV[1])
        if removed == 1 then
            return redis.call('DECR', KEYS[2])
        end
        return -1
    """;

    public ReservationResult reserve(long campaignActivityId,
            long userId,
            CampaignActivityMeta meta,
            Instant requestedAt) {

        if (meta == null) {
            return ReservationResult.error();
        }

        if (!meta.isParticipatable(requestedAt)) {
            return ReservationResult.closed();
        }

        String userKey = String.valueOf(userId);
        String userSetKey = participantsKey(campaignActivityId);
        String counterKey = counterKey(campaignActivityId);

        EntryDiagnosticStageRecorder recorder = diagnosticStageRecorder;
        long redisStartNanos = recorder == null ? 0 : System.nanoTime();
        Long result;
        try {
            result = redisTemplate.execute(
                    reservationScript,
                    List.of(userSetKey, counterKey),
                    userKey,
                    String.valueOf(meta.limitCount())
            );
        } catch (RuntimeException exception) {
            recordStage(recorder, "redis_lua_execute", "exception", redisStartNanos);
            throw exception;
        }
        if (result == null) {
            recordStage(recorder, "redis_lua_execute", "error", redisStartNanos);
            return ReservationResult.error();
        }

        if (result == -1) {
            recordStage(recorder, "redis_lua_execute", "duplicated", redisStartNanos);
            return ReservationResult.duplicated();
        }

        if (result == -2) {
            recordStage(recorder, "redis_lua_execute", "sold_out", redisStartNanos);
            return ReservationResult.soldOut();
        }

        recordStage(recorder, "redis_lua_execute", "success", redisStartNanos);

        Long order = result;
        try {
            long publishStartNanos = recorder == null ? 0 : System.nanoTime();
            eventPublisher.publishEvent(new ReservationApprovedEvent(
                    campaignActivityId,
                    userId,
                    order,
                    requestedAt,
                    meta.productId(),
                    meta.campaignActivityType()));
            if (recorder != null) {
                recorder.record("application_event_publish", "success", System.nanoTime() - publishStartNanos);
            }
        } catch (TaskRejectedException exception) {
            log.error("Reservation approved event scheduling failed. campaignActivityId={}, userId={}",
                    campaignActivityId, userId, exception);
        }

        return ReservationResult.success(order);
    }

    private void recordStage(EntryDiagnosticStageRecorder recorder,
            String stage,
            String outcome,
            long startNanos) {
        if (recorder != null) {
            recorder.record(stage, outcome, System.nanoTime() - startNanos);
        }
    }

    // 참여 기록이 남아 있을 때만 취소해 중복 취소에 따른 카운터 차감을 막는다.
    public void rollbackReservation(long campaignActivityId, long userId) {
        String userKey = String.valueOf(userId);
        String userSetKey = participantsKey(campaignActivityId);
        String counterKey = counterKey(campaignActivityId);

        redisTemplate.execute(
                cancellationScript,
                List.of(userSetKey, counterKey),
                userKey);
    }

    private String participantsKey(long campaignActivityId) {
        return "campaign:%d:users".formatted(campaignActivityId);
    }

    private String counterKey(long campaignActivityId) {
        return "campaign:%d:counter".formatted(campaignActivityId);
    }
}
