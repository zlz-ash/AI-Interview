package com.ash.springai.interview_platform.auth;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
    @NotBlank(message = "refreshToken 不能为空")
    String refreshToken
) {}
