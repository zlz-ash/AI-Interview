package com.ash.springai.interview_platform.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_role_permissions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_auth_role_permission", columnNames = {"role_id", "permission_id"})
}, indexes = {
    @Index(name = "idx_auth_role_permissions_role", columnList = "role_id"),
    @Index(name = "idx_auth_role_permissions_perm", columnList = "permission_id")
})
public class AuthRolePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private AuthRoleEntity role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private AuthPermissionEntity permission;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public static AuthRolePermissionEntity of(AuthRoleEntity role, AuthPermissionEntity permission) {
        AuthRolePermissionEntity entity = new AuthRolePermissionEntity();
        entity.setRole(role);
        entity.setPermission(permission);
        return entity;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AuthRoleEntity getRole() {
        return role;
    }

    public void setRole(AuthRoleEntity role) {
        this.role = role;
    }

    public AuthPermissionEntity getPermission() {
        return permission;
    }

    public void setPermission(AuthPermissionEntity permission) {
        this.permission = permission;
    }
}

