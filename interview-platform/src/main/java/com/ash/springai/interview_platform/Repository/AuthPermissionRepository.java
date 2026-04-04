package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthPermissionRepository extends JpaRepository<AuthPermissionEntity, Long> {
    Optional<AuthPermissionEntity> findByCode(String code);
}

