package com.axon.entry_service.reservation.v2;

import com.axon.payment.PaymentTransactions.Reservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** v2 keys are isolated from v1. Scripts assume a single Redis, not Redis Cluster. */
public final class RedisReservationHolds {
    private static final String HOLDS = "axon:v2:holds";
    private static final String UNREGISTERED = "axon:v2:unregistered";
    private static final String RETIRED = "axon:v2:retired";
    private static final String IDENTITIES = "axon:v2:hold-identities";

    private static final DefaultRedisScript<String> ADMIT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[8]) == 1 or redis.call('EXISTS', KEYS[9]) == 1 then
                return redis.error_reply('v1 activity state exists; cutover required')
            end
            local owner = redis.call('HGET', KEYS[3], ARGV[1])
            if owner then
                local existing = redis.call('HGET', KEYS[4], owner)
                if not existing then return redis.error_reply('owner without hold') end
                return "D:" .. existing
            end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return redis.error_reply('member without owner')
            end
            if redis.call('SISMEMBER', KEYS[6], ARGV[2]) == 1 then
                return redis.error_reply('reservation ID retired')
            end
            if redis.call('HEXISTS', KEYS[4], ARGV[2]) == 1 then
                return redis.error_reply('reservation ID already exists')
            end
            local count = tonumber(redis.call('GET', KEYS[2]) or '0')
            local limit = tonumber(ARGV[3])
            if not count or count < 0 or not limit or limit < 0 then
                return redis.error_reply('invalid counter or limit')
            end
            if limit > 0 and count >= limit then return '' end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('SET', KEYS[2], count + 1)
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
            redis.call('HSET', KEYS[4], ARGV[2], ARGV[4])
            redis.call('HSET', KEYS[7], ARGV[2], ARGV[6])
            redis.call('ZADD', KEYS[5], ARGV[5], ARGV[2])
            return "N:" .. tostring(count + 1) .. ":" .. ARGV[4]
            """, String.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local hold = redis.call('HGET', KEYS[4], ARGV[2])
            local identity = redis.call('HGET', KEYS[7], ARGV[2])
            if (hold and identity ~= ARGV[3] .. ':' .. ARGV[1]) or (identity and not hold) then
                return redis.error_reply('hold identity mismatch')
            end
            local owner = redis.call('HGET', KEYS[3], ARGV[1])
            if owner == ARGV[2] then
                if not hold or redis.call('SISMEMBER', KEYS[1], ARGV[1]) ~= 1 then
                    return redis.error_reply('owner without matching hold and member')
                end
                local count = tonumber(redis.call('GET', KEYS[2]) or '0')
                if not count or count < 1 then return redis.error_reply('invalid counter') end
                redis.call('SREM', KEYS[1], ARGV[1])
                redis.call('DECR', KEYS[2])
                redis.call('HDEL', KEYS[3], ARGV[1])
            end
            redis.call('HDEL', KEYS[4], ARGV[2])
            redis.call('HDEL', KEYS[7], ARGV[2])
            redis.call('SADD', KEYS[6], ARGV[2])
            redis.call('ZREM', KEYS[5], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisReservationHolds(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public record Admission(Reservation reservation, boolean newlyAdmitted, Long order) { }

    /** Empty means capacity reached. A duplicate returns its original hold, not the proposed one. */
    public Reservation admit(Reservation proposed, int limit) {
        Admission admission = admitWithResult(proposed, limit);
        return admission == null ? null : admission.reservation();
    }

    public Admission admitWithResult(Reservation proposed, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be nonnegative");
        }
        String response = redis.execute(ADMIT, keys(proposed.activityId()), Long.toString(proposed.userId()),
                proposed.id().toString(), Integer.toString(limit), encode(proposed),
                Long.toString(ceilMillis(proposed.deadline())),
                proposed.activityId() + ":" + proposed.userId());
        if (response == null) {
            throw new IllegalStateException("Redis admission returned no response");
        }
        if (response.isEmpty()) {
            return null;
        }
        if (response.startsWith("D:")) {
            return new Admission(decode(response.substring(2)), false, null);
        }
        int separator = response.indexOf(':', 2);
        if (!response.startsWith("N:") || separator < 0) {
            throw new IllegalStateException("invalid Redis admission response");
        }
        return new Admission(decode(response.substring(separator + 1)), true,
                Long.valueOf(response.substring(2, separator)));
    }

    public Reservation find(UUID id) {
        String hold = (String) redis.opsForHash().get(HOLDS, id.toString());
        return hold == null ? null : decode(hold);
    }

    public Set<String> dueUnregistered(Instant before, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return redis.opsForZSet().rangeByScore(UNREGISTERED, Double.NEGATIVE_INFINITY,
                before.toEpochMilli(), 0, limit);
    }

    public boolean isUnregistered(UUID id) {
        return redis.opsForZSet().score(UNREGISTERED, id.toString()) != null;
    }

    public void markRegistered(UUID id) {
        redis.opsForZSet().remove(UNREGISTERED, id.toString());
    }

    /** A lost response is safe to retry; a different owner is never touched. */
    public void release(Reservation reservation) {
        Long result = redis.execute(RELEASE, keys(reservation.activityId()), Long.toString(reservation.userId()),
                reservation.id().toString(), Long.toString(reservation.activityId()));
        if (result == null || result != 1L) {
            throw new IllegalStateException("Redis release returned no acknowledgement");
        }
    }

    private List<String> keys(long activityId) {
        String prefix = "axon:v2:activity:" + activityId + ":";
        return List.of(prefix + "users", prefix + "counter", prefix + "owners", HOLDS, UNREGISTERED,
                RETIRED, IDENTITIES, "campaign:" + activityId + ":users",
                "campaign:" + activityId + ":counter");
    }

    private long ceilMillis(Instant deadline) {
        long millis = deadline.toEpochMilli();
        return deadline.getNano() % 1_000_000 == 0 ? millis : Math.addExact(millis, 1);
    }

    private String encode(Reservation reservation) {
        try {
            return mapper.writeValueAsString(reservation);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize hold", e);
        }
    }

    private Reservation decode(String json) {
        try {
            return mapper.readValue(json, Reservation.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot deserialize hold", e);
        }
    }
}
