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
        String token = tokenService.generateToken("ash", List.of("ADMIN", "USER"));

        JwtTokenService.JwtUserPrincipal principal = tokenService.parseAndValidate(token);
        assertEquals("ash", principal.username());
        assertEquals(List.of("ADMIN", "USER"), principal.roles());
        assertTrue(principal.expiresAtEpochSecond() > 0);
    }

    @Test
    void shouldResolveDifferentExpiresInForRememberMe() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("0123456789abcdef0123456789abcdef");
        properties.setJwtIssuer("interview-platform");
        properties.setAccessTokenMinutes(30);
        properties.setRememberMeTokenDays(7);

        JwtTokenService tokenService = new JwtTokenService(properties);
        assertEquals(30 * 60, tokenService.resolveExpiresInSeconds(false));
        assertEquals(7 * 24 * 60 * 60, tokenService.resolveExpiresInSeconds(true));
    }
}

