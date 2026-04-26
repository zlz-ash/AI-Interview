package com.ash.springai.interview_platform.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class JwtTokenService {

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "permissions";
    private static final String CLAIM_TOKEN_TYPE = "type";
    private static final String CLAIM_SESSION_ID = "sid";
    private static final String CLAIM_TOKEN_ID = "jti";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final AuthProperties authProperties;
    private final SecretKey secretKey;

    public JwtTokenService(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.secretKey = buildKey(authProperties.getJwtSecret());
    }

    public String generateToken(String username, List<String> roles) {
        return generateAccessToken(username, roles, List.of());
    }

    public String generateToken(String username, List<String> roles, List<String> permissions, long expiresInSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresInSeconds);
        return Jwts.builder()
            .issuer(authProperties.getJwtIssuer())
            .subject(username)
            .claim(CLAIM_ROLES, roles)
            .claim(CLAIM_PERMISSIONS, permissions)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact();
    }

    public String generateAccessToken(String username, List<String> roles, List<String> permissions) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(resolveAccessExpiresInSeconds());
        return Jwts.builder()
            .issuer(authProperties.getJwtIssuer())
            .subject(username)
            .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
            .claim(CLAIM_ROLES, roles)
            .claim(CLAIM_PERMISSIONS, permissions)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact();
    }

    public String generateRefreshToken(String username, String sessionId, String tokenId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(resolveRefreshExpiresInSeconds());
        return Jwts.builder()
            .issuer(authProperties.getJwtIssuer())
            .subject(username)
            .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
            .claim(CLAIM_SESSION_ID, sessionId)
            .claim(CLAIM_TOKEN_ID, tokenId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact();
    }

    public long resolveExpiresInSeconds(boolean rememberMe) {
        if (rememberMe) {
            return authProperties.getRememberMeTokenDays() * 24 * 60 * 60;
        }
        return authProperties.getAccessTokenMinutes() * 60;
    }

    public long resolveAccessExpiresInSeconds() {
        return authProperties.getAccessTokenMinutes() * 60;
    }

    public long resolveRefreshExpiresInSeconds() {
        return authProperties.getRefreshTokenDays() * 24 * 60 * 60;
    }

    public JwtUserPrincipal parseAndValidate(String token) {
        AccessTokenPrincipal principal = parseAccessToken(token);
        return new JwtUserPrincipal(
            principal.username(),
            principal.roles(),
            principal.permissions(),
            principal.expiresAtEpochSecond()
        );
    }

    public AccessTokenPrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(authProperties.getJwtIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
        String tokenType = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (tokenType != null && !TOKEN_TYPE_ACCESS.equals(tokenType)) {
            throw new IllegalArgumentException("token type mismatch");
        }

        String username = claims.getSubject();
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get(CLAIM_ROLES, List.class);
        @SuppressWarnings("unchecked")
        List<String> permissions = claims.get(CLAIM_PERMISSIONS, List.class);
        List<String> normalizedRoles = roles == null ? List.of() : new ArrayList<>(roles);
        List<String> normalizedPermissions = permissions == null ? List.of() : new ArrayList<>(permissions);
        long issuedAt = claims.getIssuedAt().toInstant().getEpochSecond();
        long expiresAt = claims.getExpiration().toInstant().getEpochSecond();
        return new AccessTokenPrincipal(username, normalizedRoles, normalizedPermissions, issuedAt, expiresAt);
    }

    public RefreshTokenPrincipal parseRefreshToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(authProperties.getJwtIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
        String tokenType = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (!TOKEN_TYPE_REFRESH.equals(tokenType)) {
            throw new IllegalArgumentException("token type mismatch");
        }
        long issuedAt = claims.getIssuedAt().toInstant().getEpochSecond();
        long expiresAt = claims.getExpiration().toInstant().getEpochSecond();
        return new RefreshTokenPrincipal(
            claims.getSubject(),
            claims.get(CLAIM_SESSION_ID, String.class),
            claims.get(CLAIM_TOKEN_ID, String.class),
            issuedAt,
            expiresAt
        );
    }

    private SecretKey buildKey(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("app.auth.jwt-secret 不能为空");
        }

        byte[] plainBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = plainBytes;
        try {
            byte[] decoded = Decoders.BASE64.decode(secret);
            // 若配置确实是 Base64 且长度达标，则优先采用解码后的密钥
            if (decoded.length >= 32) {
                keyBytes = decoded;
            }
        } catch (IllegalArgumentException ignored) {
            keyBytes = plainBytes;
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.auth.jwt-secret 至少 32 字节");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public record JwtUserPrincipal(
        String username,
        List<String> roles,
        List<String> permissions,
        long expiresAtEpochSecond
    ) {}

    public record AccessTokenPrincipal(
        String username,
        List<String> roles,
        List<String> permissions,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond
    ) {}

    public record RefreshTokenPrincipal(
        String username,
        String sessionId,
        String tokenId,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond
    ) {}
}

