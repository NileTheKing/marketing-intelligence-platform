package com.axon.entry_service.payment.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Random, signed stage-specific credentials point to a reservation ID in Redis. */
public final class PaymentCredentials {
    public static final String PRIMARY = "v2p.";
    public static final String APPROVAL = "v2a.";
    private static final String KEY_PREFIX = "axon:v2:credential:";
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;
    private final String secret;

    public PaymentCredentials(StringRedisTemplate redis, String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("payment v2 token secret must be at least 32 bytes");
        }
        this.redis = redis;
        this.secret = secret;
    }

    public String issue(UUID reservationId, String stage, Duration ttl) {
        if ((!PRIMARY.equals(stage) && !APPROVAL.equals(stage)) || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("invalid credential stage or TTL");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = stage + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String token = value + "." + signature(value);
        redis.opsForValue().set(KEY_PREFIX + token, reservationId.toString(), ttl);
        return token;
    }

    public UUID resolve(String token, String stage) {
        if (token == null || !token.startsWith(stage)) {
            return null;
        }
        int split = token.lastIndexOf('.');
        if (split <= stage.length()) {
            return null;
        }
        String unsigned = token.substring(0, split);
        if (!MessageDigest.isEqual(signature(unsigned).getBytes(StandardCharsets.US_ASCII),
                token.substring(split + 1).getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        String reservation = redis.opsForValue().get(KEY_PREFIX + token);
        return reservation == null ? null : UUID.fromString(reservation);
    }

    private String signature(String value) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secret).hmacHex(value);
    }
}
