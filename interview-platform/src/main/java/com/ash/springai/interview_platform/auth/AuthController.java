package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.common.Result;
import com.ash.springai.interview_platform.exception.ErrorCode;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AuthController {

    private final AuthUserService authUserService;
    private final JwtTokenService jwtTokenService;
    private final RefreshSessionService refreshSessionService;

    public AuthController(
        AuthUserService authUserService,
        JwtTokenService jwtTokenService,
        RefreshSessionService refreshSessionService
    ) {
        this.authUserService = authUserService;
        this.jwtTokenService = jwtTokenService;
        this.refreshSessionService = refreshSessionService;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Result<AuthTokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        try {
            AuthUserService.AuthenticatedUser user = authUserService.authenticate(request.username(), request.password());
            long accessExpiresIn = jwtTokenService.resolveAccessExpiresInSeconds();
            long refreshExpiresIn = jwtTokenService.resolveRefreshExpiresInSeconds();
            Instant refreshExpiresAt = Instant.now().plusSeconds(refreshExpiresIn);
            RefreshSessionService.CreatedSession createdSession = refreshSessionService.createSession(
                user.userId(),
                user.username(),
                refreshExpiresAt
            );
            String accessToken = jwtTokenService.generateAccessToken(user.username(), user.roles(), user.permissions());
            String refreshToken = jwtTokenService.generateRefreshToken(
                user.username(),
                createdSession.sessionId(),
                createdSession.tokenId()
            );
            return ResponseEntity.ok(Result.success(
                AuthTokenResponse.of(user, accessToken, refreshToken, accessExpiresIn, refreshExpiresIn)
            ));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
        }
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<Result<AuthTokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            JwtTokenService.RefreshTokenPrincipal principal = jwtTokenService.parseRefreshToken(request.refreshToken());
            RefreshSessionService.RotatedSession rotated = refreshSessionService.rotate(
                principal.sessionId(),
                principal.tokenId()
            );
            AuthUserService.AuthenticatedUser user = authUserService.loadAuthenticatedUser(rotated.username());
            long accessExpiresIn = jwtTokenService.resolveAccessExpiresInSeconds();
            long refreshExpiresIn = jwtTokenService.resolveRefreshExpiresInSeconds();
            String accessToken = jwtTokenService.generateAccessToken(user.username(), user.roles(), user.permissions());
            String refreshToken = jwtTokenService.generateRefreshToken(
                user.username(),
                rotated.sessionId(),
                rotated.newTokenId()
            );
            return ResponseEntity.ok(Result.success(
                AuthTokenResponse.of(user, accessToken, refreshToken, accessExpiresIn, refreshExpiresIn)
            ));
        } catch (BadCredentialsException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
        }
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Result<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        try {
            JwtTokenService.RefreshTokenPrincipal principal = jwtTokenService.parseRefreshToken(request.refreshToken());
            refreshSessionService.revokeCurrentSession(principal.sessionId(), "USER_LOGOUT");
            return ResponseEntity.ok(Result.success(null));
        } catch (BadCredentialsException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
        }
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<Result<Map<String, Object>>> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.UNAUTHORIZED));
        }

        List<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();

        Map<String, Object> data = new HashMap<>();
        data.put("username", authentication.getName());
        data.put("roles", roles);
        return ResponseEntity.ok(Result.success(data));
    }

    @GetMapping("/api/auth/admin/ping")
    public Result<String> adminPing() {
        return Result.success("pong");
    }
}

