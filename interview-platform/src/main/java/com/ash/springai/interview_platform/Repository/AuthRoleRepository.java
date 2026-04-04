package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthRoleRepository extends JpaRepository<AuthRoleEntity, Long> {
    Optional<AuthRoleEntity> findByName(String name);
}

