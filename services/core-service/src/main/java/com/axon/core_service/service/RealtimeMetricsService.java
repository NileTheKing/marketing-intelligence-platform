package com.axon.core_service.service;

import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.CampaignActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RealtimeMetricsService {
    private final StringRedisTemplate redisTemplate;
    private final CampaignActivityRepository campaignActivityRepository;

    public Long getParticipantCount(Long activityId) {
        String value = redisTemplate.opsForValue().get("axon:v2:activity:" + activityId + ":counter");
        if (value == null) {
            value = redisTemplate.opsForValue().get("campaign:" + activityId + ":counter");
        }
        return value !=  null ? Long.parseLong(value) : 0L;
    }

    public Long getRemainingStock(Long participantCount, Long totalStock) {
        return Math.max(0, totalStock - participantCount);
    }

}
