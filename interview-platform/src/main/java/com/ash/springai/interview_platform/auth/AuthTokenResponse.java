package com.ash.springai.interview_platform.auth;

import java.util.List;

public record AuthTokenResponse(
    String tokenType,
    String accessToken,
    long accessExpiresIn,
    String refreshToken,
    long refreshExpiresIn,
    long issuedAt,
    String username,
    List<String> roles,
    List<String> permissions
) {
    public static AuthTokenResponse of(
        AuthUserService.AuthenticatedUser user,
        String accessToken,
        String refreshToken,
        long accessExpiresIn,
        long refreshExpiresIn
    ) {
        return new AuthTokenResponse(
            "Bearer",
            accessToken,
            accessExpiresIn,
            refreshToken,
            refreshExpiresIn,
            java.time.Instant.now().getEpochSecond(),
            user.username(),
            user.roles(),
            user.permissions()
        );
    }
}
