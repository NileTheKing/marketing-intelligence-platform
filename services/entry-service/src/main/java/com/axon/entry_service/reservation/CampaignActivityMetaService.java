package com.axon.entry_service.reservation;

import com.axon.entry_service.config.auth.JwtTokenProvider;
import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.dto.CampaignActivitySummaryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignActivityMetaService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final DefaultRedisScript<Long> CACHE_IF_CURRENT = new DefaultRedisScript<>("""
            local version = redis.call('GET', KEYS[2]) or '0'
            if version ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[2])
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    // 같은 키의 동시 조회는 이 Entry 인스턴스 안에서만 합친다.
    private final ConcurrentMap<Long, CompletableFuture<CampaignActivityMeta>> inFlight = new ConcurrentHashMap<>();

    private final RestClient campaignRestClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwtTokenProvider;

    public CampaignActivityMeta getMeta(Long campaignActivityId) {
        Objects.requireNonNull(campaignActivityId, "campaignActivityId must not be null");
        CampaignActivityMeta cached = readCached(campaignActivityId);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<CampaignActivityMeta> flight = new CompletableFuture<>();
        CompletableFuture<CampaignActivityMeta> existing = inFlight.putIfAbsent(campaignActivityId, flight);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException failure) {
                    throw failure;
                }
                throw e;
            }
        }
        try {
            // 앞선 조회가 캐시를 채우고 flight를 제거한 직후 진입했을 수도 있다.
            CampaignActivityMeta meta = readCached(campaignActivityId);
            if (meta == null) {
                meta = loadMeta(campaignActivityId);
            }
            flight.complete(meta);
            return meta;
        } catch (RuntimeException | Error failure) {
            flight.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(campaignActivityId, flight);
        }
    }

    private CampaignActivityMeta readCached(Long campaignActivityId) {
        String cached = redisTemplate.opsForValue().get(metaCacheKey(campaignActivityId));
        if (cached == null) {
            return null;
        }
        try {
            CampaignActivityMeta meta = objectMapper.readValue(cached, CampaignActivityMeta.class);
            // Old cache entries lack the delivery mode; reload from Core.
            if (meta.couponDeliveryMode() == null) {
                return null;
            }
            return meta;
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize campaign meta cache. id={}", campaignActivityId, e);
            redisTemplate.delete(metaCacheKey(campaignActivityId));
            return null;
        }
    }

    private CampaignActivityMeta loadMeta(Long campaignActivityId) {
        String cacheKey = metaCacheKey(campaignActivityId);
        String versionKey = cacheKey + ":version";
        String version = Objects.requireNonNullElse(redisTemplate.opsForValue().get(versionKey), "0");
        CampaignActivitySummaryResponse response = fetchCampaignActivity(campaignActivityId);
        if (response == null) {
            return null;
        }

        List<Map<String, Object>> filters = response.filters();
        boolean hasFastValidation = false;
        boolean hasHeavyValidation = false;
        if (filters != null) {
            for (Map<String, Object> filter : filters) {
                String phase = (String) filter.get("phase");
                if ("FAST".equals(phase)) {
                    hasFastValidation = true;
                } else if ("HEAVY".equals(phase)) {
                    hasHeavyValidation = true;
                }
                if (hasFastValidation && hasHeavyValidation) {
                    break;
                }
            }
        }

        CampaignActivityMeta meta = new CampaignActivityMeta(
                response.id(),
                response.campaignId(),
                response.limitCount(),
                response.status(),
                response.startDate(),
                response.endDate(),
                response.filters(),
                hasFastValidation,
                hasHeavyValidation,
                response.productId(),
                response.couponId(),
                response.activityType(),
                response.couponDeliveryMode());

        try {
            // 조회 도중 무효화됐다면 이전 응답을 캐시에 되살리지 않는다.
            // 진행 중 요청의 응답까지 최신 상태로 보장하는 것은 아니다.
            redisTemplate.execute(CACHE_IF_CURRENT, List.of(cacheKey, versionKey),
                    version, objectMapper.writeValueAsString(meta), Long.toString(CACHE_TTL.toSeconds()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize campaign meta cache. key={}", cacheKey, e);
        }

        return meta;
    }

    /**
     * Remove the cached CampaignActivityMeta for the given campaign activity ID.
     *
     * @param campaignActivityId the campaign activity identifier whose cached meta
     *                           will be removed
     */
    public void evictMeta(Long campaignActivityId) {
        String key = metaCacheKey(campaignActivityId);
        redisTemplate.execute(INVALIDATE, List.of(key, key + ":version"));
    }

    /**
     * Fetches the campaign activity summary from the external campaign API for the
     * given campaign activity ID.
     *
     * @param campaignActivityId the campaign activity identifier to retrieve
     * @return the CampaignActivitySummaryResponse for the requested ID, or `null`
     *         if the external service returns 404 (not found)
     * @throws IllegalStateException if the request fails for reasons other than a
     *                               404 Not Found
     */
    private CampaignActivitySummaryResponse fetchCampaignActivity(Long campaignActivityId) {
        try {
            String accessToken = jwtTokenProvider.generateAccessToken(0L);// system user
            return campaignRestClient.get()
                    .uri("/api/v1/campaign-activities/{id}", campaignActivityId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(CampaignActivitySummaryResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Campaign activity not found. id={}", campaignActivityId);
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch campaign activity meta. id={}", campaignActivityId, e);
            throw new IllegalStateException("Failed to fetch campaign activity meta", e);
        }
    }

    /**
     * Constructs the Redis cache key for a campaign activity's meta entry.
     *
     * @param campaignActivityId the campaign activity identifier
     * @return the cache key in the format "campaign:{id}:meta"
     */
    private String metaCacheKey(Long campaignActivityId) {
        return "campaign:%s:meta".formatted(campaignActivityId);
    }
}
