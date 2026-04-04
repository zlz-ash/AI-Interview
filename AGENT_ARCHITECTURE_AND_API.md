# 智能面试平台 — 架构与接口说明（供 Agent 阅读）

> 面向：其他 AI Agent / 新成员快速理解仓库边界与 HTTP 约定。  
> 后端版本以 `interview-platform/pom.xml`（Spring Boot 4.x + Java 21）为准；前端为独立 Vite 工程。

---

## 1. 仓库结构

| 路径 | 说明 |
|------|------|
| `interview-platform/` | Spring Boot 后端：REST API、JPA、Redis、Spring AI、JWT 认证 |
| `interview-platform-frontend/` | React + Vite + TypeScript 前端（`pnpm` / `npm` 均可） |

---

## 2. 后端技术栈（概要）

- **运行时**：Java 21  
- **框架**：Spring Boot 4.x，Spring WebMVC，Spring Security（JWT 无状态）  
- **数据**：PostgreSQL（JPA + Hibernate），**pgvector**（Spring AI Vector Store）  
- **缓存 / 会话**：Redis（Redisson）  
- **对象映射**：业务中广泛使用 **Jackson 3**（`tools.jackson.databind.ObjectMapper`）；Spring Security 异常 JSON 与 JWT 过滤器与此对齐  
- **AI**：Spring AI（OpenAI 兼容 Chat / Embedding），简历与知识库解析等（Tika、PDF 等见 `pom.xml`）  
- **对象存储**：S3 兼容（RustFS / MinIO 等，`app.storage.*`）  
- **API 文档**：springdoc-openapi（Swagger UI：`/swagger-ui/index.html`，OpenAPI JSON：`/v3/api-docs`）

---

## 3. 后端分层与包（`com.ash.springai.interview_platform`）

- **`auth/`**：JWT 签发与校验、`SecurityFilterChain`、登录与用户配置（`app.auth.*`）  
- **`controller/`**：REST 入口（Interview / Resume / KnowledgeBase / RAG Chat）  
- **`service/`**：业务逻辑  
- **`Repository/`**：Spring Data JPA  
- **`Entity/`**：JPA 实体与部分 DTO  
- **`redis/`**：会话等缓存封装  
- **`config/`**：存储、应用级配置属性等  
- **`common/`**：统一响应 `Result` 等  
- **`exception/`**：`ErrorCode` 与业务异常体系  

---

## 4. 认证与安全

### 4.1 机制

- **无状态 JWT**：请求头 `Authorization: Bearer <accessToken>`  
- **放行（无需 Token）**：`/api/auth/login`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`、`/actuator/**`  
- **角色**：`hasRole("ADMIN")` 对应权限名 `ROLE_ADMIN`；JWT 内 `roles` 与 `SimpleGrantedAuthority` 会做 `ROLE_` 前缀归一  
- **管理员路径**：`/api/auth/admin/**` 需 `ADMIN` 角色  

### 4.2 登录

- **POST** `/api/auth/login`  
- **Body（JSON）**：`username`、`password`（必填）；`rememberMe`（可选布尔）  
- **成功**：HTTP 200，`Result` 包一层，**data** 内含：`accessToken`、`tokenType`（`Bearer`）、`expiresIn`（秒）、`issuedAt`、`username`、`roles`、`rememberMe`  
- **失败**：HTTP 401，body 为 `Result` 错误形态（见下节）  

### 4.3 当前用户

- **GET** `/api/auth/me`：需 Bearer；返回当前用户名与角色列表（`Result`）  

---

## 5. 统一响应与错误（接口规范）

### 5.1 成功响应体（`Result<T>`）

后端实体字段为：

- **`code`**：`int`，成功时为 **200**（见 `CommonConstants` / `Result` 实现）  
- **`message`**：`String`，成功时常为 `"success"`  
- **`data`**：业务载荷，可为 `null`  

> 说明：Java 类 `Result` **没有** `success` 布尔字段；若客户端或文档中出现 `success`，需确认是否为前端派生或其它层封装。

### 5.2 错误

- 业务接口广泛使用 **`Result.error(ErrorCode)`** 等，**HTTP 状态码可能与 `code` 一致也可能为 200**，需同时看 **HTTP 状态** 与 **body.code**（认证失败在 Security 层可能直接 **401/403** + JSON `Result`）  
- **`ErrorCode`**：分段约定 — 通用 1xxx、简历 2xxx、面试 3xxx、存储 4xxx、导出 5xxx、知识库 6xxx、AI 7xxx、限流 8xxx 等（完整列表见 `exception/ErrorCode.java`）  

### 5.3 非 JSON 的特殊接口

- **SSE**：如 RAG 流式对话 **`POST /api/rag-chat/sessions/{sessionId}/messages/stream`**，`Content-Type: text/event-stream`，**不是** `Result` 包装  

---

## 6. 主要 REST 路由一览（均需认证除非注明）

### 6.1 认证（匿名可访问）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录，返回 JWT |
| GET | `/api/auth/me` | 当前用户（需 Bearer） |
| GET | `/api/auth/admin/ping` | 管理员探针（需 ADMIN） |

### 6.2 面试 `InterviewController`

| 方法 | 路径 |
|------|------|
| POST | `/api/interview/sessions` |
| GET | `/api/interview/sessions/{sessionId}` |
| GET | `/api/interview/sessions/{sessionId}/question` |
| POST | `/api/interview/sessions/{sessionId}/answers` |
| PUT | `/api/interview/sessions/{sessionId}/answers` |
| GET | `/api/interview/sessions/{sessionId}/report` |
| GET | `/api/interview/sessions/unfinished/{resumeId}` |
| POST | `/api/interview/sessions/{sessionId}/complete` |
| GET | `/api/interview/sessions/{sessionId}/details` |
| GET | `/api/interview/sessions/{sessionId}/export` |
| DELETE | `/api/interview/sessions/{sessionId}` |

### 6.3 简历 `ResumeController`

| 方法 | 路径 |
|------|------|
| POST | `/api/resumes/upload`（`multipart/form-data`） |
| GET | `/api/resumes` |
| GET | `/api/resumes/{id}/detail` |
| GET | `/api/resumes/{id}/export` |
| DELETE | `/api/resumes/{id}` |
| POST | `/api/resumes/{id}/reanalyze` |
| GET | `/api/resumes/health` |

### 6.4 知识库 `KnowledgeBaseController`

| 方法 | 路径 |
|------|------|
| GET | `/api/knowledgebase/list` |
| GET | `/api/knowledgebase/{id}` |
| DELETE | `/api/knowledgebase/{id}` |
| POST | `/api/knowledgebase/query` |
| POST | `/api/knowledgebase/query/stream`（SSE） |
| GET | `/api/knowledgebase/categories` |
| GET | `/api/knowledgebase/category/{category}` |
| GET | `/api/knowledgebase/uncategorized` |
| PUT | `/api/knowledgebase/{id}/category` |
| POST | `/api/knowledgebase/upload`（`multipart/form-data`） |
| GET | `/api/knowledgebase/{id}/download` |
| GET | `/api/knowledgebase/search` |
| GET | `/api/knowledgebase/stats` |
| POST | `/api/knowledgebase/{id}/revectorize` |

### 6.5 RAG 对话 `RagChatController`

| 方法 | 路径 |
|------|------|
| POST | `/api/rag-chat/sessions` |
| GET | `/api/rag-chat/sessions` |
| GET | `/api/rag-chat/sessions/{sessionId}` |
| PUT | `/api/rag-chat/sessions/{sessionId}/title` |
| PUT | `/api/rag-chat/sessions/{sessionId}/pin` |
| PUT | `/api/rag-chat/sessions/{sessionId}/knowledge-bases` |
| DELETE | `/api/rag-chat/sessions/{sessionId}` |
| POST | `/api/rag-chat/sessions/{sessionId}/messages/stream`（SSE） |

---

## 7. 配置与环境（Agent 改代码时常用）

- **主配置**：`interview-platform/src/main/resources/application.properties`  
- **本地覆盖**：`optional:classpath:templates/application-local.properties`（可放密钥与本地 URL，勿提交敏感信息）  
- **典型依赖**：PostgreSQL（默认 `localhost:5432/interview_db`）、Redis（`localhost:6379`）、向量维度与模型见 `spring.ai.*`  

---

## 8. 给 Agent 的协作提示

1. **改 API**：同步改 Controller、DTO、`ErrorCode`（如新增业务错误）、前端调用与 Swagger 行为。  
2. **改认证**：注意 `SecurityConfig` 的 `requestMatchers` 与 `JwtAuthenticationFilter.shouldNotFilter` **路径保持一致**。  
3. **JSON 序列化**：业务服务注入的 `ObjectMapper` 为 **Jackson 3**；勿与 `com.fasterxml.jackson` 混用除非显式桥接。  
4. **权威细节**：以源码与 OpenAPI 为准；本文仅作导航，**不替代** `springdoc` 生成的 schema。  

---

*文档由仓库快照整理；若与代码不一致，以当前分支源码为准。*
