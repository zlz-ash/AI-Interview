package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthUserRepository extends JpaRepository<AuthUserEntity, Long> {
    Optional<AuthUserEntity> findByUsername(String username);
    Optional<AuthUserEntity> findByUsernameAndEnabledTrue(String username);
}

