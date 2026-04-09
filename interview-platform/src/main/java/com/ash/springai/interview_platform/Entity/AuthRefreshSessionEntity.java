package com.ash.springai.interview_platform.Entity;

import com.ash.springai.interview_platform.auth.RefreshSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "auth_refresh_sessions", indexes = {
    @Index(name = "uk_auth_refresh_session_sid", columnList = "sessionId", unique = true),
    @Index(name = "idx_auth_refresh_session_user_status", columnList = "userId,status"),
    @Index(name = "idx_auth_refresh_session_expires_at", columnList = "expiresAt")
})
public class AuthRefreshSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sessionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 128)
    private String currentRefreshJti;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefreshSessionStatus status = RefreshSessionStatus.ACTIVE;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant lastRotatedAt;

    @Column
    private Instant lastSeenAt;

    @Column
    private Instant revokedAt;

    @Column(length = 128)
    private String revokeReason;

    @Column(length = 256)
    private String clientFingerprint;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public static AuthRefreshSessionEntity active(
        Long userId,
        String username,
        String sessionId,
        String currentRefreshJti,
        Instant expiresAt
    ) {
        AuthRefreshSessionEntity entity = new AuthRefreshSessionEntity();
        entity.setUserId(userId);
        entity.setUsername(username);
        entity.setSessionId(sessionId);
        entity.setCurrentRefreshJti(currentRefreshJti);
        entity.setStatus(RefreshSessionStatus.ACTIVE);
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    public void rotateTo(String newRefreshJti) {
        this.currentRefreshJti = newRefreshJti;
        this.lastRotatedAt = Instant.now();
    }

    public void markReplayLocked() {
        this.status = RefreshSessionStatus.REPLAY_LOCKED;
        this.revokeReason = "REPLAY_DETECTED";
        this.revokedAt = Instant.now();
    }

    public void revoke(String reason) {
        this.status = RefreshSessionStatus.REVOKED;
        this.revokeReason = reason;
        this.revokedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getCurrentRefreshJti() {
        return currentRefreshJti;
    }

    public void setCurrentRefreshJti(String currentRefreshJti) {
        this.currentRefreshJti = currentRefreshJti;
    }

    public RefreshSessionStatus getStatus() {
        return status;
    }

    public void setStatus(RefreshSessionStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getLastRotatedAt() {
        return lastRotatedAt;
    }

    public void setLastRotatedAt(Instant lastRotatedAt) {
        this.lastRotatedAt = lastRotatedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }

    public String getClientFingerprint() {
        return clientFingerprint;
    }

    public void setClientFingerprint(String clientFingerprint) {
        this.clientFingerprint = clientFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
