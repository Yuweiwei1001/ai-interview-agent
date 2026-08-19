# 用户自定义 LLM API Key 配置 — 设计文档

日期：2026-08-19
状态：待评审

## 1. 背景与目标

当前系统所有 LLM 调用（面试出题/评分/追问/总结、知识库问答、编程题评估）统一使用部署方在 `application-local.yml` 中配置的 DashScope API Key，成本由部署方承担。本功能允许**每个用户配置自己的 LLM API Key**，配置后该用户的全部 chat 类 LLM 调用路由到其自有 key，成本自担。

### 已确认的关键决策（与用户逐条确认）

| 决策点 | 结论 |
|---|---|
| Provider 范围 | 支持 DashScope + OpenAI 兼容协议（自定义 base_url，如 DeepSeek/Moonshot/智谱） |
| Embedding | 始终使用系统 key（text-embedding-v3），不随用户路由 |
| 回落策略 | **强制配置**：未配置 key 的用户无法使用依赖 LLM 的功能（面试/问答/编程评估），系统不提供兜底 chat 调用 |
| 模型名 | 全部开放手填；保存配置时发起一次极简真实 LLM 调用校验 key + model 组合可用性，校验通过才允许落库 |

### 非目标（YAGNI）

- 不做多 key 池化/轮询（每用户仅一条生效配置）
- 不做 key 额度/quota 管理
- 不路由 embedding 调用
- 不支持 provider 自动识别（用户显式选择）

## 2. 总体架构

核心思路：**路由代理 ChatModel**。自定义 `UserRoutingChatModel implements ChatModel` 注册为 `@Primary` Bean，替代 Spring AI 自动配置的直连模型成为所有 `ChatClient.Builder` 的底层模型。每次调用在 `call()`/`stream()` 入口按当前用户（`BaseContext.getCurrentId()`）解析真实目标模型并委托：

- 用户已配置 → 委托给按 `(provider, apiKey, baseUrl, model)` 缓存的 per-user `DashScopeChatModel` / `OpenAiChatModel`
- 用户未配置 → 抛出 `LlmKeyNotConfiguredException`（业务异常，前端引导跳转设置页）

**所有现有调用点（TechnicalAgent/ProjectAgent/CodingAgent/FollowUpGenerator/AnswerEvaluator/PlanGenerator/ChatService 等）零改动**——它们注入的 `ChatClient.Builder` 不变，底层模型被透明替换。

```
HTTP 请求线程                     llm-call-* 线程池
┌──────────────┐   快照传播    ┌──────────────────────────┐
│ BaseContext   │ ──────────► │ UserRoutingChatModel      │
│  (userId)     │  (userId)   │  ├─ resolve(userId)       │
└──────────────┘              │  ├─ cache: key→ChatModel  │
                              │  └─ delegate.call()/stream│
                              └──────────────────────────┘
```

## 3. 数据模型

### 3.1 新表 `user_llm_config`（V10__user_llm_config.sql）

```sql
CREATE TABLE user_llm_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL UNIQUE COMMENT 'app_user.id，每用户一条生效配置',
    provider        VARCHAR(20) NOT NULL COMMENT 'dashscope / openai（openai 表示 OpenAI 兼容协议）',
    api_key_enc     VARCHAR(512) NOT NULL COMMENT 'AES 加密后的 API key',
    base_url        VARCHAR(255) DEFAULT NULL COMMENT 'provider=openai 时必填，如 https://api.deepseek.com/v1',
    model           VARCHAR(64)  NOT NULL COMMENT '用户手填的模型名',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3.2 `llm_trace` 加列（同一迁移文件）

```sql
ALTER TABLE llm_trace ADD COLUMN key_source VARCHAR(10) NOT NULL DEFAULT 'system'
    COMMENT 'system=系统key / user=用户自有key（成本记0）';
```

`key_source=user` 的行 `estimated_cost` 写 0（花的是用户额度，不计入部署方成本）；观测台页面与成本汇总接口需兼容该列（成本统计只聚合 `key_source='system'`）。

## 4. 后端组件设计

新增包 `com.interview.agent.llm`（独立模块，边界清晰）：

| 类 | 职责 |
|---|---|
| `UserLlmConfig` / `UserLlmConfigMapper` | 实体与 MyBatis 映射 |
| `UserLlmConfigService` | 配置的查询/保存（含 test call 校验）/删除；解密出参供路由层使用 |
| `UserLlmConfigController` | REST 接口（见 §6） |
| `ApiKeyCipher` | AES-GCM 加解密工具；密钥从 `application-local.yml` 的 `llm.key-cipher-secret` 读取，不入库不硬编码 |
| `UserRoutingChatModel` | `@Primary` ChatModel 路由代理：解析当前用户配置 → 命中委托、未命中抛 `LlmKeyNotConfiguredException` |
| `UserChatModelFactory` | 按配置构建 per-user ChatModel（DashScope/OpenAI），带 `ConcurrentHashMap` 缓存（键 = provider+keyHash+baseUrl+model），配置更新/删除时失效对应条目 |
| `LlmKeyNotConfiguredException` | 业务异常，errorCode 约定 `LLM_KEY_NOT_CONFIGURED`，由 `GlobalExceptionHandler` 已 有的 `BaseException` 分支返回 |

### 4.1 路由代理关键行为

- `call()` 与 `stream()` 入口读取 `BaseContext.getCurrentId()`；为 null 时按"未配置"处理（抛出 `LlmKeyNotConfiguredException`），系统不开任何绕过路由的内部通道
- key_source 标记：委托给用户模型时，在 `LlmTraceContext` 扩展字段 `keySource=user`（随 LlmCallWrapper 快照传播），trace handler 读取后落 `llm_trace.key_source`
- DashScope 委托模型保留现有约束：`enable-thinking=true`（qwen3.7-max 强制）；OpenAI 兼容模型不带该参数
- 委托模型的 `defaultOptions.model` 设为用户配置的 model

### 4.2 保存时 test call 校验

`UserLlmConfigService.save` 流程：
1. 解密参数校验（provider 合法、openai 时 base_url 必填且 http/https、model 非空、key 非空）
2. 用该配置临时构建一个 ChatModel，发起极简调用（如 `Prompt("ping")`，DashScope maxTokens=10）
3. 调用成功 → 加密落库（upsert by user_id），失效缓存
4. 调用失败 → 返回明确错误（key 无效/模型不存在/网络不可达），**不落库**
5. test call 设短超时（15s），直接调临时模型、不走 LlmCallWrapper；调用前在 `LlmTraceContextHolder` 显式标记 `keySource=user`，使 Observation 自动落库的 trace 行 `key_source=user`、`estimated_cost=0`（与 §3.2 成本口径一致）

### 4.3 上下文传播（ThreadLocal 跨线程）

路由依赖 `BaseContext.getCurrentId()`，但两类调用路径的执行线程不是请求线程：

1. **LlmCallWrapper（llm-call-\* 线程池）**：已有 `LlmTraceContext` 快照/恢复先例。在 `callWithRetry` 提交前同时快照 `BaseContext.getCurrentId()`，在执行线程恢复、finally 清理。改动集中在 `LlmCallWrapper` 一处
2. **ChatService SSE 流式**：`chatClient...stream().content().subscribe()` 的 `stream()` 方法体（即路由代码）在发起订阅的线程同步执行。需确认 ask 处理所在线程持有 userId；若该处理被放入异步线程，则在该调用点显式传播（实现时验证，属计划内检查项）

### 4.4 安全

- key 落库前 AES-GCM 加密；加密密钥配置项 `llm.key-cipher-secret`，走 `application-local.yml`（与 DashScope key 同一管理机制）
- 查询接口返回脱敏 key：`sk-***` + 末 4 位
- 日志/trace 中禁止出现明文 key：`UserRoutingChatModel`/factory 不打 key 日志；`llm_trace.prompt_excerpt` 不含 header 信息，天然安全
- 配置接口强制登录态（JWT 拦截器已覆盖），userId 一律取自 `BaseContext`，不接受前端传值

## 5. 前端设计

1. **新增页面** `pages/LlmSettingsView.vue`，路由 `/settings/llm`（requiresAuth）：
   - provider 单选（DashScope / OpenAI 兼容）
   - API Key 输入（已配置时显示脱敏值 + "更换"）
   - base_url 输入（provider=openai 时显示，必填）
   - model 输入（DashScope 默认占位 `qwen3.7-max-2026-05-17`）
   - "校验并保存"按钮（后端 test call，loading 态，失败展示原因）
   - "删除配置"按钮（删除后回到未配置态）
2. **入口**：HomeView 导航加"模型设置"入口
3. **未配置引导**：前端 `request.ts` 统一拦截 `LLM_KEY_NOT_CONFIGURED` 错误码 → 全局提示"请先配置自己的 LLM API Key"并跳转设置页；面试开始页/问答页在检测到未配置时展示引导横幅（可选，依赖一个轻量 `GET /api/llm-config/status`）

## 6. API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/llm-config` | 查询当前用户配置（key 脱敏）；未配置返回 data=null |
| PUT | `/api/llm-config` | 保存配置（test call 校验通过后 upsert）；失败返回具体校验错误 |
| DELETE | `/api/llm-config` | 删除当前用户配置并失效缓存 |
| GET | `/api/llm-config/status` | 轻量状态：`{ configured: true/false }`，供前端引导横幅使用 |

请求体（PUT）：`{ provider, apiKey, baseUrl?, model }`；响应（GET）：
`{ provider, apiKeyMasked, baseUrl, model, updatedAt }`

错误码：
- `LLM_KEY_INVALID`：test call 失败（附 provider 返回的原始错误摘要）
- `LLM_KEY_NOT_CONFIGURED`：未配置用户发起 LLM 功能调用

## 7. 错误处理与降级

- `LlmKeyNotConfiguredException` 继承 `BaseException`，走现有 `GlobalExceptionHandler` 返回统一 `Result`，HTTP 200 + 业务码（与项目现有风格一致）
- 用户 key 调用失败（限流/欠费/网络）时行为与现状一致：`LlmCallWrapper` 超时+重试+降级矩阵兜底（出题→内置题库、评分→参考分等），degraded 标记照常——**不因是用户 key 改变降级策略**
- 强制配置策略下，"未配置"不是降级场景而是使用前置条件，在路由层直接拒绝，错误信息明确指引配置入口

## 8. 测试方案

1. **单元/集成（后端）**：
   - `ApiKeyCipher` 加解密往返
   - `UserLlmConfigService.save`：mock test call 成功/失败两条路径
   - `UserRoutingChatModel`：已配置用户命中委托、未配置抛异常、缓存命中/失效
   - `LlmCallWrapper` 快照传播：提交线程 userId 在执行线程可读
2. **端到端（本地环境自测，遵循现有 verify-*.ps1 惯例）**：
   - 新增 `verify-llm-config.ps1`：登录 → 保存配置（真实 DashScope key + qwen3.7-max）→ 发起知识库问答 → 查 llm_trace 确认 `key_source=user` 且 cost=0 → 删除配置 → 再发问答应返回 `LLM_KEY_NOT_CONFIGURED`
   - 前端手工验证：设置页保存/脱敏回显/删除；未配置用户进入面试被引导
3. **回归**：系统 key 场景（embedding 向量化、知识库检索）不受影响；评测模块（eval）驱动账号 testuser 需先配置有效 key 再跑 golden set 回归

## 9. 影响面与风险

| 项 | 影响 | 对策 |
|---|---|---|
| eval 评测模块 | eval 走前端入口 + testuser 登录态，属正常 HTTP 链路（执行线程有 userId），不开豁免通道；testuser 未配置 key 时评测不可用 | 自测前为 testuser 账号配置有效 key（verify-llm-config.ps1 前置步骤） |
| SSE 流式路由线程 | stream() 入口线程若无 userId 会误判未配置 | §4.3 已列为实现检查项，e2e 验证覆盖 |
| 用户配错模型（如不支持 enable-thinking 的 DashScope 模型） | 保存时 test call 已用真实模型+参数校验，可拦截 | test call 必须带与生产一致的 options（enable-thinking=true） |
| 明文 key 泄露 | 日志/trace/接口出参 | §4.4 加密+脱敏+禁打日志 |
| 老用户（已注册未配置） | 强制配置后 LLM 功能不可用 | 属产品既定决策；前端引导提示 |

## 10. 交付物清单

- 迁移：`V10__user_llm_config.sql`（新表 + llm_trace 加列）
- 后端：`com.interview.agent.llm` 包（§4 全部类）+ `LlmCallWrapper` 快照扩展 + `GlobalExceptionHandler`（如需新分支）+ trace handler 适配 `key_source`
- 前端：`LlmSettingsView.vue` + 路由 + request.ts 错误码拦截 + HomeView 入口
- 验证脚本：`verify-llm-config.ps1`
- 文档：`docs/reference` 相关章节更新（03 功能模块、04 数据模型与 API 清单、06 设计决策）
