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
@Table(name = "auth_user_roles", uniqueConstraints = {
    @UniqueConstraint(name = "uk_auth_user_role", columnNames = {"user_id", "role_id"})
}, indexes = {
    @Index(name = "idx_auth_user_roles_user", columnList = "user_id"),
    @Index(name = "idx_auth_user_roles_role", columnList = "role_id")
})
public class AuthUserRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AuthUserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private AuthRoleEntity role;

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

    public static AuthUserRoleEntity of(AuthUserEntity user, AuthRoleEntity role) {
        AuthUserRoleEntity entity = new AuthUserRoleEntity();
        entity.setUser(user);
        entity.setRole(role);
        return entity;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AuthUserEntity getUser() {
        return user;
    }

    public void setUser(AuthUserEntity user) {
        this.user = user;
    }

    public AuthRoleEntity getRole() {
        return role;
    }

    public void setRole(AuthRoleEntity role) {
        this.role = role;
    }
}

