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
    private final AuthProperties authProperties;

    public AuthController(
        AuthUserService authUserService,
        JwtTokenService jwtTokenService,
        AuthProperties authProperties
    ) {
        this.authUserService = authUserService;
        this.jwtTokenService = jwtTokenService;
        this.authProperties = authProperties;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Result<Map<String, Object>>> login(@Valid @RequestBody LoginRequest request) {
        try {
            AuthUserService.AuthenticatedUser user = authUserService.authenticate(request.username(), request.password());
            long expiresInSeconds = jwtTokenService.resolveExpiresInSeconds(request.rememberMeEnabled());
            String token = jwtTokenService.generateToken(user.username(), user.roles(), user.permissions(), expiresInSeconds);
            Map<String, Object> data = new HashMap<>();
            data.put("tokenType", "Bearer");
            data.put("accessToken", token);
            data.put("expiresIn", expiresInSeconds);
            data.put("issuedAt", Instant.now().getEpochSecond());
            data.put("username", user.username());
            data.put("roles", user.roles());
            data.put("permissions", user.permissions());
            data.put("rememberMe", request.rememberMeEnabled());
            return ResponseEntity.ok(Result.success(data));
        } catch (BadCredentialsException e) {
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

