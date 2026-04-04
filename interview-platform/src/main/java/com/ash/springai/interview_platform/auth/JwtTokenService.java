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

    private final AuthProperties authProperties;
    private final SecretKey secretKey;

    public JwtTokenService(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.secretKey = buildKey(authProperties.getJwtSecret());
    }

    public String generateToken(String username, List<String> roles) {
        long expiresInSeconds = authProperties.getAccessTokenMinutes() * 60;
        return generateToken(username, roles, expiresInSeconds);
    }

    public String generateToken(String username, List<String> roles, long expiresInSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresInSeconds);
        return Jwts.builder()
            .issuer(authProperties.getJwtIssuer())
            .subject(username)
            .claim(CLAIM_ROLES, roles)
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

    public JwtUserPrincipal parseAndValidate(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String username = claims.getSubject();
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get(CLAIM_ROLES, List.class);
        List<String> normalizedRoles = roles == null ? List.of() : new ArrayList<>(roles);
        long expiresAt = claims.getExpiration().toInstant().getEpochSecond();
        return new JwtUserPrincipal(username, normalizedRoles, expiresAt);
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

    public record JwtUserPrincipal(String username, List<String> roles, long expiresAtEpochSecond) {}
}

