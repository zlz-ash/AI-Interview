package com.ash.springai.interview_platform.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component
public class RefreshSessionRedisStore {

    private static final String KEY_PREFIX = "auth:refresh:sid:";

    private final StringRedisTemplate redisTemplate;

    public RefreshSessionRedisStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<RedisRefreshSession> get(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        Map<Object, Object> map = redisTemplate.opsForHash().entries(key);
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RedisRefreshSession(
                sessionId,
                Long.parseLong(String.valueOf(map.get("uid"))),
                String.valueOf(map.get("uname")),
                String.valueOf(map.get("jti")),
                RefreshSessionStatus.valueOf(String.valueOf(map.get("status"))),
                Long.parseLong(String.valueOf(map.get("expEpoch")))
            ));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public void save(RedisRefreshSession session, Duration ttl) {
        String key = KEY_PREFIX + session.sessionId();
        redisTemplate.opsForHash().putAll(key, Map.of(
            "uid", String.valueOf(session.userId()),
            "uname", session.username(),
            "jti", session.currentJti(),
            "status", session.status().name(),
            "expEpoch", String.valueOf(session.expEpoch())
        ));
        Duration effectiveTtl = ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl;
        redisTemplate.expire(key, effectiveTtl);
    }

    public void delete(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    public record RedisRefreshSession(
        String sessionId,
        Long userId,
        String username,
        String currentJti,
        RefreshSessionStatus status,
        long expEpoch
    ) {}
}
