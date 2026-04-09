package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthRefreshSessionEntity;
import com.ash.springai.interview_platform.Repository.AuthRefreshSessionRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RefreshSessionService {

    private static final String INVALID_REFRESH_TOKEN = "无效或过期的 refresh token";
    private static final String REPLAY_MESSAGE = "检测到异常刷新行为，请重新登录";

    private final AuthRefreshSessionRepository repository;
    private final RefreshSessionRedisStore redisStore;

    public RefreshSessionService(
        AuthRefreshSessionRepository repository,
        RefreshSessionRedisStore redisStore
    ) {
        this.repository = repository;
        this.redisStore = redisStore;
    }

    public CreatedSession createSession(Long userId, String username, Instant expiresAt) {
        String sessionId = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        AuthRefreshSessionEntity entity = AuthRefreshSessionEntity.active(
            userId,
            username,
            sessionId,
            refreshJti,
            expiresAt
        );
        repository.save(entity);
        redisStore.save(toRedis(entity), ttl(entity.getExpiresAt()));
        return new CreatedSession(sessionId, refreshJti, userId, username, expiresAt.getEpochSecond());
    }

    public RotatedSession rotate(String sessionId, String presentedJti) {
        AuthRefreshSessionEntity entity = repository.findBySessionId(sessionId)
            .orElseThrow(() -> new BadCredentialsException(INVALID_REFRESH_TOKEN));
        if (entity.getStatus() != RefreshSessionStatus.ACTIVE) {
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }
        if (!Objects.equals(entity.getCurrentRefreshJti(), presentedJti)) {
            entity.markReplayLocked();
            repository.save(entity);
            redisStore.save(toRedis(entity), ttl(entity.getExpiresAt()));
            throw new BadCredentialsException(REPLAY_MESSAGE);
        }
        String newJti = UUID.randomUUID().toString();
        entity.rotateTo(newJti);
        repository.save(entity);
        redisStore.save(toRedis(entity), ttl(entity.getExpiresAt()));
        return new RotatedSession(entity.getSessionId(), newJti, entity.getUserId(), entity.getUsername());
    }

    public void revokeCurrentSession(String sessionId, String reason) {
        repository.findBySessionId(sessionId).ifPresent(entity -> {
            entity.revoke(reason);
            repository.save(entity);
            redisStore.delete(sessionId);
        });
    }

    private RefreshSessionRedisStore.RedisRefreshSession toRedis(AuthRefreshSessionEntity entity) {
        return new RefreshSessionRedisStore.RedisRefreshSession(
            entity.getSessionId(),
            entity.getUserId(),
            entity.getUsername(),
            entity.getCurrentRefreshJti(),
            entity.getStatus(),
            entity.getExpiresAt().getEpochSecond()
        );
    }

    private Duration ttl(Instant expiresAt) {
        return Duration.between(Instant.now(), expiresAt);
    }

    public record CreatedSession(
        String sessionId,
        String tokenId,
        Long userId,
        String username,
        long expiresAtEpochSecond
    ) {}

    public record RotatedSession(
        String sessionId,
        String newTokenId,
        Long userId,
        String username
    ) {}
}
