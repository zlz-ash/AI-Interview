# Spring Security + JWT 双 Token + RBAC 后端设计（方案 A）

## 1. 背景与目标

当前项目已具备 `Spring Security + JWT + RBAC` 基础能力，但登录态仍以单 Access Token 为主（通过 rememberMe 延长时效），缺少独立 Refresh Token 生命周期与服务端可撤销会话管理。本次设计聚焦后端全量升级，前端改造由 ash 后续自行对接。

本设计目标：

- 引入 Access/Refresh 双 Token 机制；
- 引入 Refresh Token 服务端持久化与可撤销能力；
- 支持 Refresh Token 轮换与重放检测；
- 保持现有 RBAC 授权模型，且以数据库为唯一真相源；
- 登出能力先支持“当前设备（当前会话）失效”。

## 2. 范围与非目标

### 2.1 本次范围（In Scope）

- 后端认证接口与鉴权链路改造：
  - `POST /api/auth/login`
  - `POST /api/auth/refresh`
  - `POST /api/auth/logout`
  - `GET /api/auth/me`（兼容增强）
- Access/Refresh JWT 签发与校验能力；
- Refresh 会话状态存储（PostgreSQL + Redis）；
- RBAC 权限仍由数据库装配；
- 单元测试与集成测试补齐认证关键路径。

### 2.2 非目标（Out of Scope）

- 前端 token 存储、自动刷新、请求重放逻辑；
- 全设备强退（仅预留扩展，不在本次落地）；
- OAuth2/OIDC 外部身份提供方接入；
- 多因子认证。

## 3. 现状评估（基于当前代码）

- 已存在：
  - `SecurityConfig` 无状态会话与 `JwtAuthenticationFilter`；
  - `AuthController` 的 `/api/auth/login` 与 `/api/auth/me`；
  - `JwtTokenService`（单 token 签发/解析，含 roles/permissions claim）；
  - RBAC 数据模型（用户、角色、权限及关联）；
  - 统一错误返回 `Result`。
- 当前缺口：
  - 无独立 Refresh Token；
  - 无 Refresh 会话持久化与撤销；
  - 无 Refresh 轮换与重放检测；
  - rememberMe 通过延长 Access 过期实现，安全边界偏弱。

## 4. 方案选型与结论

已确认采用 **方案 A**：

- Access Token：JWT，自包含用户权限快照，用于请求鉴权；
- Refresh Token：JWT（带 `sid` / `jti` / `type=refresh`），用于换发新双 token；
- 服务端状态：`PostgreSQL + Redis` 混合存储 Refresh 会话状态；
- 刷新策略：每次刷新执行 Refresh Token 轮换，并做重放检测。

选型理由：

- 与现有 JWT 架构兼容，改造路径最稳；
- 可同时满足安全性（撤销、重放检测）与性能（Redis 快速校验）；
- 易于后续扩展到全设备强退。

## 5. 核心架构设计

### 5.1 Token 模型

- Access Token（默认 60 分钟）：
  - 关键 claim：`sub`, `roles`, `permissions`, `iat`, `exp`, `iss`, `type=access`
- Refresh Token（默认 30 天）：
  - 关键 claim：`sub`, `sid`, `jti`, `iat`, `exp`, `iss`, `type=refresh`

约束：

- Access Token 不落库，过期后通过 Refresh 获取新 token；
- Refresh Token 必须与服务端会话状态一致才可使用；
- 每次刷新后，旧 Refresh Token 立即失效。

### 5.2 会话状态模型

- 业务主键：`sid`（会话 ID，UUID）
- 当前合法 refresh 指针：`current_refresh_jti`
- 状态机：
  - `ACTIVE`：正常可刷新
  - `REVOKED`：用户登出或管理失效
  - `REPLAY_LOCKED`：检测到重放后锁定
  - `EXPIRED`：自然过期（可通过清理任务维护）

### 5.3 存储职责划分

- PostgreSQL：持久化真相源与审计轨迹；
- Redis：在线鉴权快速通道（低延迟，TTL 对齐 refresh 到期）。

读写规则：

- 写路径优先 DB 成功，再写 Redis；
- 读路径优先 Redis，未命中回源 DB 并回填 Redis；
- 任一存储判定会话失效，刷新请求均拒绝。

## 6. 数据模型设计

新增表建议：`auth_refresh_session`

字段建议：

- `id` BIGSERIAL PK
- `session_id` VARCHAR(64) UNIQUE NOT NULL
- `user_id` BIGINT NOT NULL
- `username` VARCHAR(100) NOT NULL
- `current_refresh_jti` VARCHAR(128) NOT NULL
- `status` VARCHAR(32) NOT NULL
- `expires_at` TIMESTAMP NOT NULL
- `last_rotated_at` TIMESTAMP NULL
- `last_seen_at` TIMESTAMP NULL
- `revoked_at` TIMESTAMP NULL
- `revoke_reason` VARCHAR(128) NULL
- `client_fingerprint` VARCHAR(256) NULL
- `created_at` TIMESTAMP NOT NULL
- `updated_at` TIMESTAMP NOT NULL

索引建议：

- `uk_auth_refresh_session_session_id(session_id)`
- `idx_auth_refresh_session_user_status(user_id, status)`
- `idx_auth_refresh_session_expires_at(expires_at)`

## 7. Redis Key 设计

主键：

- `auth:refresh:sid:{sid}`（Hash）

字段：

- `uid`：用户 ID
- `uname`：用户名
- `jti`：当前合法 refresh jti
- `status`：会话状态
- `expEpoch`：到期秒级时间戳

TTL：

- 与 refresh token 到期时间一致（30 天窗口内按实际 exp 对齐）。

## 8. 接口契约

### 8.1 `POST /api/auth/login`

请求：

- `username`
- `password`
- `rememberMe`（保留字段，不再决定是否签发 refresh，仅可作为客户端提示）

响应（data）：

- `tokenType`: `Bearer`
- `accessToken`
- `accessExpiresIn`
- `refreshToken`
- `refreshExpiresIn`
- `issuedAt`
- `username`
- `roles`
- `permissions`

处理流程：

1. 用户认证（账号密码）；
2. 从 DB 解析角色权限；
3. 生成 `sid` + 初始 `jti`；
4. 创建会话（DB + Redis）；
5. 签发并返回双 token。

### 8.2 `POST /api/auth/refresh`

请求：

- `refreshToken`

响应（data）：

- 同登录响应结构，返回新双 token。

校验与轮换流程：

1. 验签并校验 `type=refresh` 与过期时间；
2. 解析 `sid/jti/sub`；
3. 查询会话状态（优先 Redis，失败回源 DB）；
4. 校验：
   - `status == ACTIVE`
   - 传入 `jti == current_refresh_jti`
5. 通过后生成新 `jti`，原子更新会话；
6. 签发新 Access + 新 Refresh。

重放检测：

- 若 `sid` 存在但 `jti` 不匹配：
  - 将会话状态置为 `REPLAY_LOCKED`；
  - 记录 `revoke_reason=REPLAY_DETECTED`；
  - 返回 401，要求重新登录。

### 8.3 `POST /api/auth/logout`

请求：

- `refreshToken`

行为：

- 解析 `sid` 并将该会话置 `REVOKED`；
- 清理或失效 Redis 会话；
- 幂等返回成功。

说明：

- 当前仅支持“当前设备（当前会话）失效”。

### 8.4 `GET /api/auth/me`

- 沿用 Access Token 鉴权；
- 返回用户名与当前授权快照（角色/权限）；
- 不承担刷新逻辑。

## 9. Spring Security 与代码结构改造点

### 9.1 安全配置

- 继续 `SessionCreationPolicy.STATELESS`；
- 放行：
  - `/api/auth/login`
  - `/api/auth/refresh`
  - `/api/auth/logout`
- 其他接口继续基于 `hasRole/hasAuthority` 控制。

### 9.2 组件职责

- `JwtTokenService`：
  - 增加 Access/Refresh 差异化签发与解析接口；
  - 对 claim 中 `type/sid/jti` 做强校验。
- `RefreshSessionService`（新增）：
  - `createSession(...)`
  - `rotateSession(...)`
  - `revokeCurrentSession(...)`
  - `markReplayLocked(...)`
- `RefreshSessionRepository`（新增，JPA）；
- `RefreshSessionRedisStore`（新增，Redis 访问封装）；
- `AuthController`：
  - 扩展 login 返回；
  - 新增 refresh/logout 端点。

### 9.3 RBAC 原则

- 运行期权限只来源于数据库；
- 配置文件用户仅用于初始化/开发辅助，不作为生产授权真相源。

## 10. 错误语义与安全策略

错误码：

- `401`：token 无效、过期、会话失效、重放触发
- `403`：已认证但权限不足

建议错误文案：

- 通用 refresh 失败：`无效或过期的 refresh token`
- 重放锁定：`检测到异常刷新行为，请重新登录`

安全细节：

- 刷新接口只接受 `type=refresh` token；
- Access token 不可用于 refresh/logout；
- 会话状态变更需记录时间与原因字段；
- 关键失败日志需包含 `sid`（不记录原始 token）。

## 11. 配置项变更

新增/调整：

- `app.auth.access-token-minutes=60`
- `app.auth.refresh-token-days=30`
- `app.auth.jwt-issuer=interview-platform`（沿用）
- 可选开关：`app.auth.refresh-rotate-on-use=true`

兼容约束：

- 保留 `app.auth.remember-me-token-days` 一到两个版本用于平滑迁移；
- 代码层不再依赖 rememberMe 决定 refresh 存在性。

## 12. 测试与验收标准

### 12.1 单元测试

- 登录签发双 token；
- refresh 成功后 `jti` 发生变化；
- 使用旧 refresh 触发 `REPLAY_LOCKED`；
- logout 后 refresh 失败。

### 12.2 集成测试

- `login -> refresh -> old refresh replay -> relogin` 全链路；
- Redis 未命中场景可回源 DB 并继续工作；
- `401/403` 返回结构与现有统一 Result 保持一致。

### 12.3 回归测试

- 现有受保护业务接口在 Access 鉴权上行为不回退；
- RBAC 权限命中逻辑不回退。

验收标准：

- 后端完整提供双 token + 可撤销刷新会话能力；
- 重放检测可触发并阻断会话；
- 当前设备登出后该会话不可继续刷新；
- 相关测试通过且无既有授权回归。

## 13. 风险与缓解

- Redis 与 DB 短暂不一致：
  - 缓解：读路径回源 DB，失败以保守拒绝为准；
- 高并发刷新竞争：
  - 缓解：DB 侧基于 `session_id + current_refresh_jti` 条件更新保证原子轮换；
- 前端尚未接入自动刷新：
  - 缓解：后端契约稳定后由前端按新字段对接。

## 14. 里程碑建议

1. 数据模型与存储层落地；
2. Token 服务与会话服务落地；
3. 控制器接口改造与安全配置调整；
4. 测试补齐与回归；
5. 发布并观察认证失败日志（重放/过期分布）。

---

本设计对应 ash 已确认决策：

- 全量后端改造（前端后续自行接入）；
- Refresh 持久化采用 Redis + PostgreSQL；
- 刷新策略为轮换 + 重放检测；
- RBAC 以数据库为唯一真相源；
- 登出粒度为当前会话；
- 默认时效为 Access 60 分钟、Refresh 30 天。

## 实现状态（更新）

- [x] Access/Refresh 双 Token 签发与解析能力已落地
- [x] Refresh 会话状态已支持 PostgreSQL 持久化与 Redis 缓存映射
- [x] 已实现 refresh 轮换与 replay 检测（命中后 REPLAY_LOCKED）
- [x] 已支持当前会话 logout 失效
