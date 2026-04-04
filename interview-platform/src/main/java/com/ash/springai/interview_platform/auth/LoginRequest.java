package com.ash.springai.interview_platform.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "username 不能为空")
    String username,
    @NotBlank(message = "password 不能为空")
    String password,
    Boolean rememberMe
) {
    public boolean rememberMeEnabled() {
        return Boolean.TRUE.equals(rememberMe);
    }
}

