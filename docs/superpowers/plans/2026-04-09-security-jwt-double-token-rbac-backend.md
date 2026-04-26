# Spring Security JWT 双 Token + RBAC（后端）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有认证体系上落地 Access/Refresh 双 Token、Refresh 会话可撤销、轮换与重放检测，并保持 RBAC（DB 真相源）不回退。

**Architecture:** 保持 Access JWT 无状态鉴权链路不变，新增 Refresh JWT 专用链路与会话状态层。会话状态采用 PostgreSQL 持久化 + Redis 热数据，刷新时以 `sid + current_jti` 做原子轮换并检测重放。控制器扩展登录返回并新增 refresh/logout 端点，错误语义继续走统一 `Result`。

**Tech Stack:** Spring Boot 4, Spring Security, Spring Data JPA, Redis (Redisson/StringRedisTemplate), JJWT, JUnit 5, Mockito, MockMvc

---

## 文件结构与职责分解

### 将修改的现有文件

- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthProperties.java`
  - 新增 refresh 过期配置，保留旧 rememberMe 配置做平滑兼容。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/JwtTokenService.java`
  - 增加 Access/Refresh 区分签发与解析，新增 claim `type/sid/jti`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthController.java`
  - 登录响应改为双 token；新增 `/api/auth/refresh`、`/api/auth/logout`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/SecurityConfig.java`
  - 放行 refresh/logout 端点；保留其余授权策略。
- `interview-platform/src/main/resources/application.properties`
  - 增加 `app.auth.refresh-token-days=30`，将 `app.auth.access-token-minutes=60`。
- `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/JwtTokenServiceTests.java`
  - 覆盖 Access/Refresh 签发与解析分支。
- `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java`
  - 覆盖 login/refresh/replay/logout 链路。

### 将新增的文件

- `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/AuthRefreshSessionEntity.java`
  - Refresh 会话持久化实体（含 `sessionId/currentRefreshJti/status/expiresAt/revokeReason`）。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/AuthRefreshSessionRepository.java`
  - 会话查询与原子轮换更新入口。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionStatus.java`
  - 会话状态枚举：`ACTIVE/REVOKED/REPLAY_LOCKED/EXPIRED`。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionRedisStore.java`
  - Redis 会话读写封装，隔离 KV 细节。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionService.java`
  - 会话创建、轮换、重放锁定、当前会话登出。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshTokenRequest.java`
  - 刷新接口请求体。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/LogoutRequest.java`
  - 登出接口请求体。
- `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthTokenResponse.java`
  - 登录与刷新统一响应 DTO。
- `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/RefreshSessionServiceTests.java`
  - 轮换成功、旧 jti 重放锁定、登出失效测试。

---

### Task 1: 扩展 Token 配置与 JWT 双类型能力

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthProperties.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/JwtTokenService.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/JwtTokenServiceTests.java`

- [ ] **Step 1: 先写失败测试（Refresh claim 与过期策略）**

```java
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
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\mvnw.cmd -Dtest=JwtTokenServiceTests test`  
Expected: FAIL，提示 `setRefreshTokenDays` / `generateRefreshToken` / `parseRefreshToken` 未定义。

- [ ] **Step 3: 最小实现配置与服务能力**

```java
// AuthProperties.java
private long refreshTokenDays = 30;
public long getRefreshTokenDays() { return refreshTokenDays; }
public void setRefreshTokenDays(long refreshTokenDays) { this.refreshTokenDays = refreshTokenDays; }

// JwtTokenService.java (核心新增)
private static final String CLAIM_TOKEN_TYPE = "type";
private static final String CLAIM_SESSION_ID = "sid";
private static final String CLAIM_TOKEN_ID = "jti";

public String generateAccessToken(String username, List<String> roles, List<String> permissions) {
    long expiresIn = authProperties.getAccessTokenMinutes() * 60;
    return issueToken(username, expiresIn, claims -> {
        claims.put(CLAIM_TOKEN_TYPE, "access");
        claims.put("roles", roles);
        claims.put("permissions", permissions);
    });
}

public String generateRefreshToken(String username, String sessionId, String tokenId) {
    long expiresIn = authProperties.getRefreshTokenDays() * 24 * 60 * 60;
    return issueToken(username, expiresIn, claims -> {
        claims.put(CLAIM_TOKEN_TYPE, "refresh");
        claims.put(CLAIM_SESSION_ID, sessionId);
        claims.put(CLAIM_TOKEN_ID, tokenId);
    });
}

public AccessTokenPrincipal parseAccessToken(String token) {
    Claims claims = parseClaims(token);
    if (!"access".equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
        throw new IllegalArgumentException("token type mismatch");
    }
    return new AccessTokenPrincipal(
        claims.getSubject(),
        claims.get("roles", List.class),
        claims.get("permissions", List.class),
        claims.getIssuedAt().toInstant().getEpochSecond(),
        claims.getExpiration().toInstant().getEpochSecond()
    );
}

public RefreshTokenPrincipal parseRefreshToken(String token) {
    Claims claims = parseClaims(token);
    if (!"refresh".equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
        throw new IllegalArgumentException("token type mismatch");
    }
    return new RefreshTokenPrincipal(
        claims.getSubject(),
        claims.get(CLAIM_SESSION_ID, String.class),
        claims.get(CLAIM_TOKEN_ID, String.class),
        claims.getIssuedAt().toInstant().getEpochSecond(),
        claims.getExpiration().toInstant().getEpochSecond()
    );
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `.\mvnw.cmd -Dtest=JwtTokenServiceTests test`  
Expected: PASS，输出包含 `BUILD SUCCESS` 与 `Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthProperties.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/JwtTokenService.java interview-platform/src/test/java/com/ash/springai/interview_platform/auth/JwtTokenServiceTests.java
git commit -m "feat(auth): add access and refresh jwt primitives"
```

---

### Task 2: 落地 Refresh 会话持久化模型（PostgreSQL）

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/AuthRefreshSessionEntity.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/AuthRefreshSessionRepository.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionStatus.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/RefreshSessionServiceTests.java`

- [ ] **Step 1: 写失败测试（仓储查询与状态切换约束）**

```java
@Test
void shouldMarkReplayLockedWhenJtiMismatch() {
    AuthRefreshSessionEntity entity = AuthRefreshSessionEntity.active(1L, "ash", "sid-1", "jti-new", Instant.now().plusSeconds(3600));
    assertEquals(RefreshSessionStatus.ACTIVE, entity.getStatus());
    entity.markReplayLocked();
    assertEquals(RefreshSessionStatus.REPLAY_LOCKED, entity.getStatus());
    assertEquals("REPLAY_DETECTED", entity.getRevokeReason());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\mvnw.cmd -Dtest=RefreshSessionServiceTests#shouldMarkReplayLockedWhenJtiMismatch test`  
Expected: FAIL，提示 `AuthRefreshSessionEntity` 与 `RefreshSessionStatus` 不存在。

- [ ] **Step 3: 最小实现实体、枚举、仓储**

```java
// RefreshSessionStatus.java
public enum RefreshSessionStatus { ACTIVE, REVOKED, REPLAY_LOCKED, EXPIRED }

// AuthRefreshSessionEntity.java (关键字段)
@Column(nullable = false, unique = true, length = 64)
private String sessionId;
@Column(nullable = false, length = 128)
private String currentRefreshJti;
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 32)
private RefreshSessionStatus status;
@Column(nullable = false)
private Instant expiresAt;

public void markReplayLocked() {
    this.status = RefreshSessionStatus.REPLAY_LOCKED;
    this.revokeReason = "REPLAY_DETECTED";
    this.revokedAt = Instant.now();
}

// AuthRefreshSessionRepository.java
Optional<AuthRefreshSessionEntity> findBySessionId(String sessionId);
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `.\mvnw.cmd -Dtest=RefreshSessionServiceTests#shouldMarkReplayLockedWhenJtiMismatch test`  
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/Entity/AuthRefreshSessionEntity.java interview-platform/src/main/java/com/ash/springai/interview_platform/Repository/AuthRefreshSessionRepository.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionStatus.java interview-platform/src/test/java/com/ash/springai/interview_platform/auth/RefreshSessionServiceTests.java
git commit -m "feat(auth): add refresh session persistence model"
```

---

### Task 3: 落地 Redis 会话存储与服务编排

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionRedisStore.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionService.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthProperties.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/RefreshSessionServiceTests.java`

- [ ] **Step 1: 写失败测试（轮换成功与旧 jti 重放）**

```java
@Test
void shouldRotateRefreshTokenAndRejectOldJtiReplay() {
    AuthRefreshSessionRepository repository = mock(AuthRefreshSessionRepository.class);
    RefreshSessionRedisStore redisStore = mock(RefreshSessionRedisStore.class);
    AuthRefreshSessionEntity entity = AuthRefreshSessionEntity.active(
        1L, "ash", "sid-1", "jti-1", Instant.now().plusSeconds(86400)
    );
    when(repository.findBySessionId("sid-1")).thenReturn(Optional.of(entity));

    RefreshSessionService service = new RefreshSessionService(repository, redisStore);
    RefreshSessionService.RotatedSession rotated = service.rotate("sid-1", "jti-1");
    assertNotEquals("jti-1", rotated.newTokenId());

    BadCredentialsException ex = assertThrows(
        BadCredentialsException.class,
        () -> service.rotate("sid-1", "jti-1")
    );
    assertEquals("检测到异常刷新行为，请重新登录", ex.getMessage());
    assertEquals(RefreshSessionStatus.REPLAY_LOCKED, entity.getStatus());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\mvnw.cmd -Dtest=RefreshSessionServiceTests#shouldRotateRefreshTokenAndRejectOldJtiReplay test`  
Expected: FAIL，提示 `RefreshSessionService` 或 `rotate` 方法未定义。

- [ ] **Step 3: 最小实现服务与 Redis 存储**

```java
// RefreshSessionRedisStore.java
public Optional<RedisRefreshSession> get(String sessionId) {
    String key = "auth:refresh:sid:" + sessionId;
    Map<Object, Object> map = redisTemplate.opsForHash().entries(key);
    if (map == null || map.isEmpty()) return Optional.empty();
    return Optional.of(new RedisRefreshSession(
        sessionId,
        String.valueOf(map.get("uid")),
        String.valueOf(map.get("uname")),
        String.valueOf(map.get("jti")),
        RefreshSessionStatus.valueOf(String.valueOf(map.get("status"))),
        Long.parseLong(String.valueOf(map.get("expEpoch")))
    ));
}

public void save(RedisRefreshSession session, Duration ttl) {
    String key = "auth:refresh:sid:" + session.sessionId();
    redisTemplate.opsForHash().putAll(key, Map.of(
        "uid", session.userId(),
        "uname", session.username(),
        "jti", session.currentJti(),
        "status", session.status().name(),
        "expEpoch", String.valueOf(session.expEpoch())
    ));
    redisTemplate.expire(key, ttl);
}

public void delete(String sessionId) {
    redisTemplate.delete("auth:refresh:sid:" + sessionId);
}

// RefreshSessionService.java (关键逻辑)
public RotatedSession rotate(String sessionId, String presentedJti) {
    AuthRefreshSessionEntity entity = repository.findBySessionId(sessionId)
        .orElseThrow(() -> new BadCredentialsException("无效或过期的 refresh token"));
    if (entity.getStatus() != RefreshSessionStatus.ACTIVE) { throw new BadCredentialsException("无效或过期的 refresh token"); }
    if (!Objects.equals(entity.getCurrentRefreshJti(), presentedJti)) {
        entity.markReplayLocked();
        repository.save(entity);
        redisStore.save(new RedisRefreshSession(
            entity.getSessionId(),
            String.valueOf(entity.getUserId()),
            entity.getUsername(),
            entity.getCurrentRefreshJti(),
            entity.getStatus(),
            entity.getExpiresAt().getEpochSecond()
        ), Duration.between(Instant.now(), entity.getExpiresAt()));
        throw new BadCredentialsException("检测到异常刷新行为，请重新登录");
    }
    String newJti = UUID.randomUUID().toString();
    entity.rotateTo(newJti);
    repository.save(entity);
    redisStore.save(new RedisRefreshSession(
        entity.getSessionId(),
        String.valueOf(entity.getUserId()),
        entity.getUsername(),
        newJti,
        entity.getStatus(),
        entity.getExpiresAt().getEpochSecond()
    ), Duration.between(Instant.now(), entity.getExpiresAt()));
    return new RotatedSession(entity.getSessionId(), newJti, entity.getUserId(), entity.getUsername());
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `.\mvnw.cmd -Dtest=RefreshSessionServiceTests test`  
Expected: PASS，输出包含 `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionRedisStore.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshSessionService.java interview-platform/src/test/java/com/ash/springai/interview_platform/auth/RefreshSessionServiceTests.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthProperties.java
git commit -m "feat(auth): add refresh session service with replay detection"
```

---

### Task 4: 扩展认证接口（login/refresh/logout）

**Files:**
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthTokenResponse.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshTokenRequest.java`
- Create: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/LogoutRequest.java`
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthController.java`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java`

- [ ] **Step 1: 写失败测试（接口契约）**

```java
@Test
void shouldRefreshAndReturnNewTokenPair() throws Exception {
    String refreshToken = loginAndGet("refreshToken");
    mockMvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\mvnw.cmd -Dtest=AuthApiIntegrationTests#shouldRefreshAndReturnNewTokenPair test`  
Expected: FAIL，`/api/auth/refresh` 404 或断言字段缺失。

- [ ] **Step 3: 最小实现 DTO 与控制器**

```java
// RefreshTokenRequest.java / LogoutRequest.java
public record RefreshTokenRequest(@NotBlank String refreshToken) {}
public record LogoutRequest(@NotBlank String refreshToken) {}

// AuthTokenResponse.java
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
) {}

// AuthController.java (新增端点)
@PostMapping("/api/auth/refresh")
public ResponseEntity<Result<AuthTokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    JwtTokenService.RefreshTokenPrincipal principal = jwtTokenService.parseRefreshToken(request.refreshToken());
    RefreshSessionService.RotatedSession rotated = refreshSessionService.rotate(
        principal.sessionId(),
        principal.tokenId()
    );
    AuthUserService.AuthenticatedUser user = authUserService.loadAuthenticatedUser(rotated.username());
    String accessToken = jwtTokenService.generateAccessToken(user.username(), user.roles(), user.permissions());
    String refreshToken = jwtTokenService.generateRefreshToken(user.username(), rotated.sessionId(), rotated.newTokenId());
    return ResponseEntity.ok(Result.success(AuthTokenResponse.of(user, accessToken, refreshToken, 60 * 60, 30L * 24 * 60 * 60)));
}

@PostMapping("/api/auth/logout")
public ResponseEntity<Result<Void>> logout(@Valid @RequestBody LogoutRequest request) {
    JwtTokenService.RefreshTokenPrincipal principal = jwtTokenService.parseRefreshToken(request.refreshToken());
    refreshSessionService.revokeCurrentSession(principal.sessionId(), "USER_LOGOUT");
    return ResponseEntity.ok(Result.success(null));
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `.\mvnw.cmd -Dtest=AuthApiIntegrationTests test`  
Expected: PASS，新增用例覆盖 refresh、replay、logout。

- [ ] **Step 5: 提交**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthController.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/AuthTokenResponse.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/RefreshTokenRequest.java interview-platform/src/main/java/com/ash/springai/interview_platform/auth/LogoutRequest.java interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java
git commit -m "feat(auth): add refresh and logout endpoints for token sessions"
```

---

### Task 5: 更新 Security 放行规则与配置项

**Files:**
- Modify: `interview-platform/src/main/java/com/ash/springai/interview_platform/auth/SecurityConfig.java`
- Modify: `interview-platform/src/main/resources/application.properties`
- Test: `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java`

- [ ] **Step 1: 写失败测试（refresh/logout 可匿名访问但仅接受 refresh token）**

```java
@Test
void shouldAllowAuthRefreshRouteThroughSecurityChain() throws Exception {
    mockMvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"bad\"}"))
        .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\mvnw.cmd -Dtest=AuthApiIntegrationTests#shouldAllowAuthRefreshRouteThroughSecurityChain test`  
Expected: FAIL，若被主鉴权拦截则会出现 401 来源不正确或 403。

- [ ] **Step 3: 最小实现配置**

```java
// SecurityConfig.java
.requestMatchers(
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/logout",
    "/error",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/actuator/**"
).permitAll()

// application.properties
app.auth.access-token-minutes=60
app.auth.refresh-token-days=30
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `.\mvnw.cmd -Dtest=AuthApiIntegrationTests test`  
Expected: PASS，安全放行与错误语义一致。

- [ ] **Step 5: 提交**

```bash
git add interview-platform/src/main/java/com/ash/springai/interview_platform/auth/SecurityConfig.java interview-platform/src/main/resources/application.properties interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java
git commit -m "chore(auth): expose refresh/logout routes and token ttl config"
```

---

### Task 6: 全链路回归与文档对齐

**Files:**
- Modify: `interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java`
- Modify: `docs/superpowers/specs/2026-04-09-security-jwt-rbac-design.md`

- [ ] **Step 1: 写失败测试（完整链路 + RBAC 不回退）**

```java
@Test
void shouldCompleteLoginRefreshReplayLogoutFlow() throws Exception {
    // 1) login -> get access1/refresh1
    // 2) refresh(refresh1) -> access2/refresh2
    // 3) refresh(refresh1 old) -> 401 + replay message
    // 4) logout(refresh2) -> 200
    // 5) refresh(refresh2) -> 401
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\mvnw.cmd -Dtest=AuthApiIntegrationTests#shouldCompleteLoginRefreshReplayLogoutFlow test`  
Expected: FAIL，直到控制器与服务完整串联。

- [ ] **Step 3: 补最小实现并对齐 spec 的“实现状态”备注**

```markdown
## 实现状态（更新）
- [x] 双 Token 已落地
- [x] Refresh 会话 DB + Redis
- [x] 重放检测与 REPLAY_LOCKED
- [x] 当前会话 logout 失效
```

- [ ] **Step 4: 跑认证测试集合**

Run: `.\mvnw.cmd -Dtest=JwtTokenServiceTests,RefreshSessionServiceTests,AuthApiIntegrationTests test`  
Expected: PASS，三组测试全绿。

- [ ] **Step 5: 提交**

```bash
git add interview-platform/src/test/java/com/ash/springai/interview_platform/auth/AuthApiIntegrationTests.java docs/superpowers/specs/2026-04-09-security-jwt-rbac-design.md
git commit -m "test(auth): add end-to-end refresh rotation and replay coverage"
```

---

## 执行顺序建议

1. Task 1（先稳定 token 基础能力）
2. Task 2（持久化模型）
3. Task 3（服务编排 + 重放检测）
4. Task 4（API 对外暴露）
5. Task 5（安全链与配置）
6. Task 6（全链路验证）

## 计划自检（已完成）

### 1) Spec 覆盖检查

- 双 token：Task 1, Task 4
- Redis + PostgreSQL：Task 2, Task 3
- 轮换 + 重放检测：Task 3, Task 6
- RBAC 不回退：Task 4, Task 6
- 当前会话登出：Task 4, Task 6
- TTL 60m/30d：Task 1, Task 5

未发现遗漏需求。

### 2) 占位符扫描

- 已检查：无 `TODO` / `TBD` / “后续实现”类占位词。

### 3) 类型与命名一致性

- `RefreshSessionStatus`、`AuthRefreshSessionEntity`、`RefreshSessionService`、`AuthTokenResponse` 在各任务内命名一致。
- `sid/currentRefreshJti` 语义在存储、服务、接口一致。

