package com.axon.entry_service.service.payment;

import com.axon.entry_service.dto.payment.PaymentApprovalPayload;
import com.axon.messaging.dto.payment.ReservationTokenPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReservationTokenService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final String secretTokenKey;

    public ReservationTokenService(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${payment.token.secret}") String secretTokenKey) {
        this.redisTemplate = redisTemplate;
        this.secretTokenKey = secretTokenKey;
    }

    private static final String TOKEN_PREFIX = "RESERVATION_TOKEN:";
    private static final String APPROVAL_PREFIX = "PAYMENT_APPROVED_TOKEN:";

    private static final long TOKEN_TTL_MINUTES = 5;
    private static final long APPROVALTOKEN_TTL_MINUTES = 30;

    private String hmacSha256Hex(String data) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secretTokenKey).hmacHex(data);
    }

    // HMAC 토큰 생성
    public String generateDeterministicToken(Long userId, Long campaignActivityId) {
        // 1. Payload 생성
        String payload = userId + ":" + campaignActivityId;

        // 2. HMAC-SHA256 서명 (ThreadLocal 인스턴스 재사용)
        String signature = hmacSha256Hex(payload);

        // 3. 결합
        String combined = payload + ":" + signature;

        // 4. Base64 URL-Safe 인코딩
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    // 1차 토큰 생성
    public String issueToken(ReservationTokenPayload payload) {
        String token = generateDeterministicToken(payload.getUserId(), payload.getCampaignActivityId());
        String redisKey = TOKEN_PREFIX + token;

        // 무조건 저장 및 TTL 갱신 (덮어쓰기)
        redisTemplate.opsForValue().set(redisKey, payload, TOKEN_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("1차 토큰 발급/갱신: userId={}, campaignActivityId={}",
                payload.getUserId(), payload.getCampaignActivityId());

        return token;
    }

    // 1차 토큰 유효성 확인
    public boolean isReservationTokenValid(String reservationToken) {
        String redisKey = TOKEN_PREFIX + reservationToken;
        return redisTemplate.hasKey(redisKey);
    }

    // 1차 토큰 삭제
    public void removeToken(String token) {
        String redisKey = TOKEN_PREFIX + token;
        redisTemplate.delete(redisKey);
    }

    // 1차 토큰 조회
    public Optional<ReservationTokenPayload> getPayloadFromToken(String token) {
        String redisKey = TOKEN_PREFIX + token;
        Object payload = redisTemplate.opsForValue().get(redisKey);

        if (payload != null) {
            log.debug("토큰 검증 성공 (Redis)");
            return Optional.of((ReservationTokenPayload) payload);
        }

        // Redis에 없으면 토큰 자체를 검증 (오버 엔지니어링 또는 부하가 예상되면 뺄 예정)
        if (verifyTokenSignature(token)) {
            log.debug("토큰 서명은 유효하지만 Redis에 없음 (만료 또는 첫 시도)");
        } else {
            log.warn("토큰 검증 실패 (위변조 또는 잘못된 형식)");
        }

        return Optional.empty();
    }

    // 토큰 탈취 위험이 감지 되면 시행되는 강력 검증기
    private boolean verifyTokenSignature(String token) {
        try {
            // 1. Base64 디코딩
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token),
                    StandardCharsets.UTF_8
            );

            // 2. 파싱
            String[] parts = decoded.split(":");
            if (parts.length != 3) {
                log.warn("토큰 형식 오류 (parts != 3)");
                return false;
            }

            Long userId = Long.parseLong(parts[0]);
            Long campaignActivityId = Long.parseLong(parts[1]);
            String providedSignature = parts[2];

            // 3. 서명 재계산 (ThreadLocal 인스턴스 재사용)
            String payload = userId + ":" + campaignActivityId;
            String expectedSignature = hmacSha256Hex(payload);

            // 4. 비교
            boolean valid = MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.US_ASCII),
                    providedSignature.getBytes(StandardCharsets.US_ASCII));

            if (!valid) {
                log.warn("토큰 서명 불일치 (위변조 시도): userId={}, campaignActivityId={}", userId, campaignActivityId);
            }

            return valid;

        } catch (Exception e) {
            log.error("토큰 검증 중 예외 발생", e);
            return false;
        }
    }

    // 1차 토큰 payload 조회
    public ReservationTokenPayload getPayload(String token) {
        String redisKey = TOKEN_PREFIX + token;
        return (ReservationTokenPayload) redisTemplate.opsForValue().get(redisKey);
    }

    // 2차 토큰 생성 또는 refresh
    public String createApprovalToken(PaymentApprovalPayload paymentApprovalPayload) {
        String redisKey = paymentApprovalPayload.getUserId() + ":" + paymentApprovalPayload.getCampaignActivityId();
        String approvalToken = approvalRedisKey(redisKey);

        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(approvalToken))) {
                redisTemplate.expire(approvalToken, APPROVALTOKEN_TTL_MINUTES, TimeUnit.MINUTES);
            } else {
                redisTemplate.opsForValue().set(approvalToken, paymentApprovalPayload, APPROVALTOKEN_TTL_MINUTES, TimeUnit.MINUTES);
                log.info("2차 토큰 신규 발급: userId={}, campaignActivityId={}, TTL={}분",
                        paymentApprovalPayload.getUserId(), paymentApprovalPayload.getCampaignActivityId(), APPROVALTOKEN_TTL_MINUTES);
            }
            return redisKey;
        } catch (Exception e) {
            log.error("2차 토큰 발급 실패", e);
            return null;
        }
    }

    // 2차 토큰 조회
    public Optional<PaymentApprovalPayload> getApprovalPayload(String token) {
        String redisKey = approvalRedisKey(token);
        Object payload = redisTemplate.opsForValue().get(redisKey);
        return Optional.ofNullable((PaymentApprovalPayload) payload);
    }

    // 2차 토큰 삭제
    public void removeApprovalToken(String token) {
        redisTemplate.delete(approvalRedisKey(token));
    }

    // 1차 + 2차 토큰 전체 삭제
    public void cleanup(PaymentApprovalPayload payload) {
        try {
            removeToken(payload.getReservationToken());
            removeApprovalToken(payload.getUserId() + ":" + payload.getCampaignActivityId());
            log.info("토큰 정리 완료: userId={}, campaignActivityId={}", payload.getUserId(), payload.getCampaignActivityId());
        } catch (Exception e) {
            log.error("토큰 정리 중 오류 발생 (TTL로 자동 만료됨): userId={}, error={}", payload.getUserId(), e.getMessage());
        }
    }

    private String approvalRedisKey(String key) {
        return APPROVAL_PREFIX + key;
    }
}
