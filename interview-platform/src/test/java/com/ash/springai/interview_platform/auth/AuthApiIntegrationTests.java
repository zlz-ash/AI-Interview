package com.ash.springai.interview_platform.auth;

import com.ash.springai.interview_platform.Entity.AuthRefreshSessionEntity;
import com.ash.springai.interview_platform.Entity.AuthRoleEntity;
import com.ash.springai.interview_platform.Entity.AuthUserEntity;
import com.ash.springai.interview_platform.Entity.AuthUserRoleEntity;
import com.ash.springai.interview_platform.Repository.AuthRefreshSessionRepository;
import com.ash.springai.interview_platform.Repository.AuthRolePermissionRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRepository;
import com.ash.springai.interview_platform.Repository.AuthUserRoleRepository;
import com.ash.springai.interview_platform.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthApiIntegrationTests {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("0123456789abcdef0123456789abcdef");
        properties.setJwtIssuer("interview-platform");
        properties.setAccessTokenMinutes(30);
        properties.setRefreshTokenDays(30);

        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        AuthUserRepository repository = mock(AuthUserRepository.class);
        AuthUserRoleRepository userRoleRepository = mock(AuthUserRoleRepository.class);
        AuthRolePermissionRepository rolePermissionRepository = mock(AuthRolePermissionRepository.class);
        AuthRefreshSessionRepository refreshSessionRepository = mock(AuthRefreshSessionRepository.class);
        RefreshSessionRedisStore redisStore = mock(RefreshSessionRedisStore.class);
        ConcurrentMap<String, AuthRefreshSessionEntity> sessionStore = new ConcurrentHashMap<>();

        when(refreshSessionRepository.save(any(AuthRefreshSessionEntity.class))).thenAnswer(invocation -> {
            AuthRefreshSessionEntity entity = invocation.getArgument(0);
            sessionStore.put(entity.getSessionId(), entity);
            return entity;
        });
        when(refreshSessionRepository.findBySessionId(anyString())).thenAnswer(invocation -> {
            String sid = invocation.getArgument(0);
            return Optional.ofNullable(sessionStore.get(sid));
        });
        doNothing().when(redisStore).delete(anyString());

        AuthUserEntity ash = AuthUserEntity.of("ash", encoder.encode("123456"), true);
        ash.setId(1L);
        AuthUserEntity bob = AuthUserEntity.of("bob", encoder.encode("123456"), true);
        bob.setId(2L);
        when(repository.findByUsernameAndEnabledTrue("ash")).thenReturn(Optional.of(ash));
        when(repository.findByUsernameAndEnabledTrue("bob")).thenReturn(Optional.of(bob));
        AuthRoleEntity adminRole = AuthRoleEntity.of("ADMIN", "admin");
        AuthRoleEntity userRole = AuthRoleEntity.of("USER", "user");
        when(userRoleRepository.findByUserId(1L)).thenReturn(java.util.List.of(AuthUserRoleEntity.of(ash, adminRole)));
        when(userRoleRepository.findByUserId(2L)).thenReturn(java.util.List.of(AuthUserRoleEntity.of(bob, userRole)));
        when(rolePermissionRepository.findPermissionCodesByUserId(1L))
            .thenReturn(java.util.List.of("USER:READ", "SESSION:ACCESS", "INTERVIEW:ACCESS", "RAG:ACCESS", "ADMIN:ACCESS"));
        when(rolePermissionRepository.findPermissionCodesByUserId(2L))
            .thenReturn(java.util.List.of("USER:READ", "SESSION:ACCESS", "INTERVIEW:ACCESS", "RAG:ACCESS"));

        AuthUserService authUserService = new AuthUserService(repository, userRoleRepository, rolePermissionRepository, encoder);
        JwtTokenService tokenService = new JwtTokenService(properties);
        RefreshSessionService refreshSessionService = new RefreshSessionService(refreshSessionRepository, redisStore);
        AuthController authController = new AuthController(authUserService, tokenService, refreshSessionService);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(tokenService, objectMapper);

        this.mockMvc = MockMvcBuilders
            .standaloneSetup(authController, new SecuredProbeController())
            .addFilters(jwtFilter, new RequireAuthenticationFilter(objectMapper))
            .build();
    }

    @Test
    void shouldLoginAndAccessProtectedEndpointWithJwt() throws Exception {
        String body = """
            {
              "username": "ash",
              "password": "123456",
              "rememberMe": true
            }
            """;

        String responseBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode node = objectMapper.readTree(responseBody);
        String token = node.path("data").path("accessToken").asText();
        long accessExpiresIn = node.path("data").path("accessExpiresIn").asLong();
        org.junit.jupiter.api.Assertions.assertEquals(30 * 60, accessExpiresIn);

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.username").value("ash"));
    }

    @Test
    void shouldRefreshAndReturnNewTokenPair() throws Exception {
        String body = """
            {
              "username": "ash",
              "password": "123456"
            }
            """;

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode node = objectMapper.readTree(loginResponse);
        String refreshToken = node.path("data").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenMissing() throws Exception {
        mockMvc.perform(get("/api/probe/secure"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldReturnForbiddenWhenRoleInsufficient() throws Exception {
        String body = """
            {
              "username": "bob",
              "password": "123456"
            }
            """;
        String responseBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode node = objectMapper.readTree(responseBody);
        String token = node.path("data").path("accessToken").asText();

        mockMvc.perform(get("/api/auth/admin/ping")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldRejectReplayRefreshToken() throws Exception {
        String body = """
            {
              "username": "ash",
              "password": "123456"
            }
            """;
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String refreshToken1 = objectMapper.readTree(loginResponse).path("data").path("refreshToken").asText();

        String refreshResponse = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String refreshToken2 = objectMapper.readTree(refreshResponse).path("data").path("refreshToken").asText();
        org.junit.jupiter.api.Assertions.assertTrue(refreshToken2 != null && !refreshToken2.isBlank());

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldLogoutCurrentSessionAndInvalidateRefreshToken() throws Exception {
        String body = """
            {
              "username": "ash",
              "password": "123456"
            }
            """;
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String refreshToken = objectMapper.readTree(loginResponse).path("data").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldReturnUnifiedResultWhenLoginFailed() throws Exception {
        String body = """
            {
              "username": "ash",
              "password": "wrong-password"
            }
            """;
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @RestController
    static class SecuredProbeController {
        @GetMapping("/api/probe/secure")
        public Result<String> secure() {
            return Result.success("ok");
        }
    }

    static class RequireAuthenticationFilter extends OncePerRequestFilter {

        private final ObjectMapper objectMapper;

        RequireAuthenticationFilter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
        ) throws ServletException, java.io.IOException {
            try {
                String uri = request.getRequestURI();
                if (uri.startsWith("/api/auth/login")
                    || uri.startsWith("/api/auth/refresh")
                    || uri.startsWith("/api/auth/logout")) {
                    filterChain.doFilter(request, response);
                    return;
                }
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getWriter(), Result.error(401, "未授权"));
                    return;
                }
                if (uri.startsWith("/api/auth/admin/")) {
                    boolean isAdmin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));
                    if (!isAdmin) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        objectMapper.writeValue(response.getWriter(), Result.error(403, "禁止访问"));
                        return;
                    }
                }
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}

