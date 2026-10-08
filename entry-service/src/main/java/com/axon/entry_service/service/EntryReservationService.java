package com.axon.entry_service.service;

import com.axon.entry_service.config.diagnostic.EntryDiagnosticStageRecorder;
import com.axon.entry_service.domain.CampaignActivityMeta;
import com.axon.entry_service.domain.ReservationResult;
import com.axon.entry_service.event.ReservationApprovedEvent;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

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

    /**
     * Attempt to reserve a participation slot for a user in a campaign activity.
     *
     * @param campaignActivityId the campaign activity identifier
     * @param userId             the user identifier attempting the reservation
     * @param meta               campaign activity metadata used to determine
     *                           participation eligibility and limit
     * @param requestedAt        the timestamp of the reservation request (used to
     *                           check participatability)
     * @return a {@code ReservationResult} indicating the outcome:
     *         {@code success(order)} with the allocated order number on success;
     *         {@code duplicated} if the user already reserved;
     *         {@code soldOut} if the activity's limit was reached;
     *         {@code closed} if the activity is not open at {@code requestedAt};
     *         {@code error} on invalid input or unexpected failures.
     */
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

    /**
     * Removes a user's reservation from the participant set for the specified
     * campaign activity in Redis.
     * This will also decrement the reservation counter if the user was actually
     * removed.
     *
     * @param campaignActivityId the campaign activity identifier whose participant
     *                           set will be modified
     * @param userId             the user identifier to remove from the participant
     *                           set
     */
    public void rollbackReservation(long campaignActivityId, long userId) {
        String userKey = String.valueOf(userId);
        String userSetKey = participantsKey(campaignActivityId);
        String counterKey = counterKey(campaignActivityId);

        redisTemplate.execute(
                cancellationScript,
                List.of(userSetKey, counterKey),
                userKey);
    }

    /**
     * Builds the Redis key for the participants set of a campaign activity.
     *
     * @return the Redis key in the form
     *         {@code "campaign:<campaignActivityId>:users"}
     */
    private String participantsKey(long campaignActivityId) {
        return "campaign:%d:users".formatted(campaignActivityId);
    }

    /**
     * Builds the Redis counter key for the given campaign activity.
     *
     * @return the Redis key in the form "campaign:{id}:counter"
     */
    private String counterKey(long campaignActivityId) {
        return "campaign:%d:counter".formatted(campaignActivityId);
    }
}
