package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import com.ash.springai.interview_platform.Entity.AuthUserRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuthUserRoleRepository extends JpaRepository<AuthUserRoleEntity, Long> {
    List<AuthUserRoleEntity> findByUserId(Long userId);
    Optional<AuthUserRoleEntity> findByUserAndRole(AuthUserEntity user, AuthRoleEntity role);
}

