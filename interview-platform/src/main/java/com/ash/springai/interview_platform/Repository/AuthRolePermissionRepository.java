package com.ash.springai.interview_platform.Repository;

import com.ash.springai.interview_platform.Entity.AuthPermissionEntity;
import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import com.ash.springai.interview_platform.Entity.AuthRolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuthRolePermissionRepository extends JpaRepository<AuthRolePermissionEntity, Long> {

    Optional<AuthRolePermissionEntity> findByRoleAndPermission(AuthRoleEntity role, AuthPermissionEntity permission);

    @Query("""
        select distinct rp.permission.code
        from AuthRolePermissionEntity rp
        join AuthUserRoleEntity ur on ur.role = rp.role
        where ur.user.id = :userId
          and ur.user.enabled = true
          and rp.role.enabled = true
          and rp.permission.enabled = true
    """)
    List<String> findPermissionCodesByUserId(@Param("userId") Long userId);
}

