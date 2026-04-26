package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthRefreshSessionEntity;
import com.ash.springai.interview_platform.auth.RefreshSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface AuthRefreshSessionRepository extends JpaRepository<AuthRefreshSessionEntity, Long> {
    Optional<AuthRefreshSessionEntity> findBySessionId(String sessionId);

    @Modifying
    @Transactional
    @Query("""
        update AuthRefreshSessionEntity s
           set s.currentRefreshJti = :newJti,
               s.lastRotatedAt = :rotatedAt
         where s.sessionId = :sessionId
           and s.status = :activeStatus
           and s.currentRefreshJti = :expectedJti
        """)
    int rotateRefreshJtiIfMatch(
        @Param("sessionId") String sessionId,
        @Param("expectedJti") String expectedJti,
        @Param("newJti") String newJti,
        @Param("rotatedAt") Instant rotatedAt,
        @Param("activeStatus") RefreshSessionStatus activeStatus
    );

    @Modifying
    @Transactional
    @Query("""
        update AuthRefreshSessionEntity s
           set s.status = :lockedStatus,
               s.revokeReason = :revokeReason,
               s.revokedAt = :revokedAt
         where s.sessionId = :sessionId
           and s.status = :activeStatus
        """)
    int markReplayLockedIfActive(
        @Param("sessionId") String sessionId,
        @Param("lockedStatus") RefreshSessionStatus lockedStatus,
        @Param("revokeReason") String revokeReason,
        @Param("revokedAt") Instant revokedAt,
        @Param("activeStatus") RefreshSessionStatus activeStatus
    );
}
