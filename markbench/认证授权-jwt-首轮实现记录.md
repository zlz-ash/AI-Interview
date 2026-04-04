# 认证授权（JWT）首轮实现记录

日期：2026-04-03

## 本步目标

- 以 ashpower 流程完成 JWT 认证授权最小闭环。
- 先测后改：先补单元与集成测试，再实现安全链路。

## 本步改动

- 新增认证模块：
  - `AuthProperties`
  - `AuthUserService`
  - `JwtTokenService`
  - `JwtAuthenticationFilter`
  - `SecurityConfig`
  - `AuthController`
  - `LoginRequest`
- 补充配置项：
  - `app.auth.jwt-secret`
  - `app.auth.jwt-issuer`
  - `app.auth.access-token-minutes`
  - `app.auth.users[*]`
- 补充依赖：
  - `spring-boot-starter-security`
  - `jjwt-*`
  - `spring-boot-starter-test`
  - `spring-security-test`

## 测试先行与验证

- 新增单元测试：`JwtTokenServiceTests`
- 新增集成测试：`AuthApiIntegrationTests`
- 关键命令：
  - `./mvnw "-Dtest=JwtTokenServiceTests,AuthApiIntegrationTests" test`
- 结果：
  - Tests run: 3
  - Failures: 0
  - Errors: 0
  - BUILD SUCCESS

## 过程问题与修复

- 问题：JWT secret 在 Base64 解码后长度不足，导致初始化失败。
- 修复：`JwtTokenService` 采用“Base64 可用且长度达标才使用，否则回退原始字节”的密钥策略。

## 当前结论

- 已完成最小可用 JWT 认证闭环：
  - 登录签发 token
  - 受保护接口校验 token
  - 缺失/无效 token 返回 401
- 下一步可扩展：
  - Refresh Token
  - 更细粒度角色授权（`@PreAuthorize`）
  - 用户来源从配置迁移到数据库

## 审查反馈后的加固（2026-04-04）

- 已落地 `rememberMe` 7 天策略：
  - `LoginRequest` 增加 `rememberMe`
  - `JwtTokenService` 新增过期秒数解析
  - `AuthController` 确保响应 `expiresIn` 与 JWT 过期一致
  - `application.properties` 新增 `app.auth.remember-me-token-days=7`
- 已统一登录失败响应结构：
  - `AuthController.login` 失败时返回 `401 + Result.error(...)`
- 已接入 `PasswordEncoder.matches`：
  - `AuthUserService` 从手写密码比较改为编码器匹配
- 已增加重复用户名配置防护：
  - 启动时检测到重复用户名直接抛错
- 已增加 `/api/auth/me` 显式匿名保护：
  - 未认证或匿名认证时返回 `401`

### 最新验证

- 命令：`./mvnw "-Dtest=JwtTokenServiceTests,AuthApiIntegrationTests" test`
- 结果：
  - Tests run: 6
  - Failures: 0
  - Errors: 0
  - BUILD SUCCESS

## 权限链路补齐（2026-04-04）

- 新增管理员受保护接口：`/api/auth/admin/ping`
- 新增授权规则：`/api/auth/admin/**` 需 `ADMIN` 角色
- 新增用例：普通用户携带有效 token 访问管理员接口，返回 `403`

### 本轮验证

- 命令：`./mvnw "-Dtest=JwtTokenServiceTests,AuthApiIntegrationTests" test`
- 结果：
  - Tests run: 7
  - Failures: 0
  - Errors: 0
  - BUILD SUCCESS
