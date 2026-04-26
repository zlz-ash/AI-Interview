package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthRefreshSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthRefreshSessionRepository extends JpaRepository<AuthRefreshSessionEntity, Long> {
    Optional<AuthRefreshSessionEntity> findBySessionId(String sessionId);
}
