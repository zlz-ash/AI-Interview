package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthRefreshSessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.springai.interview_platform.Repository.AuthRefreshSessionRepository;

class RefreshSessionServiceTests {

    @Test
    void shouldMarkReplayLockedWhenJtiMismatch() {
        AuthRefreshSessionEntity entity = AuthRefreshSessionEntity.active(
            1L,
            "ash",
            "sid-1",
            "jti-new",
            Instant.now().plusSeconds(3600)
        );
        assertEquals(RefreshSessionStatus.ACTIVE, entity.getStatus());
        entity.markReplayLocked();
        assertEquals(RefreshSessionStatus.REPLAY_LOCKED, entity.getStatus());
        assertEquals("REPLAY_DETECTED", entity.getRevokeReason());
    }

    @Test
    void shouldRotateRefreshTokenAndRejectOldJtiReplay() {
        AuthRefreshSessionRepository repository = mock(AuthRefreshSessionRepository.class);
        RefreshSessionRedisStore redisStore = mock(RefreshSessionRedisStore.class);
        AuthRefreshSessionEntity entity = AuthRefreshSessionEntity.active(
            1L, "ash", "sid-1", "jti-1", Instant.now().plusSeconds(86400)
        );
        when(repository.findBySessionId("sid-1")).thenReturn(Optional.of(entity));

        RefreshSessionService service = new RefreshSessionService(repository, redisStore);
        RefreshSessionService.RotatedSession rotated = service.rotate("sid-1", "jti-1");
        assertNotEquals("jti-1", rotated.newTokenId());

        BadCredentialsException ex = assertThrows(
            BadCredentialsException.class,
            () -> service.rotate("sid-1", "jti-1")
        );
        assertEquals("检测到异常刷新行为，请重新登录", ex.getMessage());
        assertEquals(RefreshSessionStatus.REPLAY_LOCKED, entity.getStatus());
    }
}
