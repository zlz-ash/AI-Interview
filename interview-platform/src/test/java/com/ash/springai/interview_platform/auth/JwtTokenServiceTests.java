package com.ash.springai.interview_platform.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTests {

    @Test
    void shouldGenerateAndParseTokenWithRoles() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("0123456789abcdef0123456789abcdef");
        properties.setJwtIssuer("interview-platform");
        properties.setAccessTokenMinutes(30);

        JwtTokenService tokenService = new JwtTokenService(properties);
        String token = tokenService.generateAccessToken("ash", List.of("ADMIN", "USER"), List.of("USER:READ"));

        JwtTokenService.AccessTokenPrincipal principal = tokenService.parseAccessToken(token);
        assertEquals("ash", principal.username());
        assertEquals(List.of("ADMIN", "USER"), principal.roles());
        assertTrue(principal.expiresAtEpochSecond() > 0);
    }

    @Test
    void shouldGenerateAndParseRefreshTokenWithSidAndJti() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("0123456789abcdef0123456789abcdef");
        properties.setJwtIssuer("interview-platform");
        properties.setAccessTokenMinutes(60);
        properties.setRefreshTokenDays(30);

        JwtTokenService tokenService = new JwtTokenService(properties);
        String token = tokenService.generateRefreshToken("ash", "sid-1", "jti-1");

        JwtTokenService.RefreshTokenPrincipal principal = tokenService.parseRefreshToken(token);
        assertEquals("ash", principal.username());
        assertEquals("sid-1", principal.sessionId());
        assertEquals("jti-1", principal.tokenId());
        assertTrue(principal.expiresAtEpochSecond() > principal.issuedAtEpochSecond());
    }

    @Test
    void shouldResolveDifferentExpiresInForAccessAndRefresh() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("0123456789abcdef0123456789abcdef");
        properties.setJwtIssuer("interview-platform");
        properties.setAccessTokenMinutes(60);
        properties.setRefreshTokenDays(30);

        JwtTokenService tokenService = new JwtTokenService(properties);
        assertEquals(60 * 60, tokenService.resolveAccessExpiresInSeconds());
        assertEquals(30L * 24 * 60 * 60, tokenService.resolveRefreshExpiresInSeconds());
    }
}

