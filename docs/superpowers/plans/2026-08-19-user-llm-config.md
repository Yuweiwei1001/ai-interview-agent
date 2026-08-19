# 用户自定义 LLM API Key 配置 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 允许每个用户配置自己的 LLM API Key（DashScope / OpenAI 兼容），配置后其全部 chat 类 LLM 调用路由到自有 key；未配置用户被引导配置；token 用量按用户维度记录并在设置页展示。

**Architecture:** 自定义 `UserRoutingChatModel`（`@Primary`）作为所有 `ChatClient` 的底层模型，运行时按 `BaseContext` userId 路由到缓存的 per-user ChatModel；未配置抛业务异常（code=1001）。配置存 `user_llm_config` 表（AES-GCM 加密 key）；`llm_trace` 加 `key_source`/`user_id` 两列支撑用量统计。

**Tech Stack:** Spring Boot 3.5.8 / Spring AI 1.1.2 / spring-ai-alibaba 1.1.2.0 / MyBatis（注解式）/ Flyway / Vue3 + naive-ui + tailwind。

**Spec:** `docs/superpowers/specs/2026-08-19-user-llm-config-design.md`（已批准）

**分支约束（用户明确要求）：** `feature/user-llm-config` 分支开发并自测通过后，**停在合并前——不合入 master、不删 worktree、不清理分支**，等用户明确指示后再操作。

**关键已验证事实（实现者无需重新调研）：**
- `DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(opts).observationRegistry(reg).build()` ✅（javap 验证）
- `DashScopeApi.builder().apiKey(String).build()` ✅；`DashScopeChatOptions.builder().model(String).enableThinking(Boolean).maxToken(Integer).build()` ✅
- `OpenAiChatModel.builder().openAiApi(api).defaultOptions(opts).observationRegistry(reg).build()` ✅；`OpenAiApi.builder().baseUrl(String).apiKey(String).build()` ✅；`OpenAiChatOptions.builder().model(String).maxTokens(Integer).build()` ✅
- `spring-ai-openai` 1.1.2 已在本地仓库；引**核心包**（非 starter）避免触发 OpenAI 自动配置
- `InterviewService`（L119/L227）与 `EvalRunner`（L64）已在异步线程设置 `BaseContext`；**只有 `ChatService.runAsk`（rag-chat 线程）与 `LlmCallWrapper`（llm-call-* 池）缺传播**
- `ChatService` 流式调用的 observation 行拿不到 usage（token/cost 恒 0），现有方案已手动落 trace（`recordLlmTrace`，cost 恒 ZERO）；本计划在其上补 `keySource`/`userId`
- 错误码用 **int**（Result/BaseException 体系）：`1001`=LLM_KEY_NOT_CONFIGURED，`1002`=LLM_KEY_INVALID（对 spec 字符串码的落地细化）
- 手动构建的 ChatModel **必须显式传 `observationRegistry`**，否则 llm_trace 不落库
- Controller 路径风格 `/api/xxx`；前端 api 惯例 `res.data.data`

---

### Task 0: 创建 worktree 隔离工作区

按用户既定工作流：从 master 创建功能分支并检出到独立 worktree，后续所有开发在 worktree 内进行。

- [ ] **Step 1: 创建 worktree**

```powershell
git -C d:\IdeaProjects\ai-interview-agent worktree add d:\IdeaProjects\ai-interview-agent-llm-config -b feature/user-llm-config master
```

Expected: `Preparing worktree...` 成功，无报错。

- [ ] **Step 2: 复制本地密钥配置到 worktree**

`application-local.yml` 被 gitignore，worktree 中不存在，自测需要：

```powershell
Copy-Item d:\IdeaProjects\ai-interview-agent\src\main\resources\application-local.yml d:\IdeaProjects\ai-interview-agent-llm-config\src\main\resources\application-local.yml
```

- [ ] **Step 3: 确认 worktree 状态**

```powershell
git -C d:\IdeaProjects\ai-interview-agent-llm-config status --short; git -C d:\IdeaProjects\ai-interview-agent-llm-config branch --show-current
```

Expected: 分支 `feature/user-llm-config`，工作区干净（application-local.yml 被 gitignore 不显示）。

---

### Task 1: pom 依赖 + V10 数据库迁移

**Files:**
- Modify: `pom.xml`（在 DashScope starter 依赖后加 spring-ai-openai 核心包）
- Create: `src/main/resources/db/migration/V10__user_llm_config.sql`

- [ ] **Step 1: pom.xml 加依赖**（插入在 `spring-ai-alibaba-starter-dashscope` 依赖块之后）

```xml
        <!-- OpenAI 兼容协议 ChatModel（用户自定义 base_url 的 LLM key；核心包，不触发自动配置） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai</artifactId>
        </dependency>
```

- [ ] **Step 2: 创建 V10 迁移**

```sql
-- ==================== 用户自定义 LLM API Key 配置 ====================
-- 每用户一条生效配置；api_key_enc 为 AES-GCM 密文（密钥在 application-local.yml 的 llm.key-cipher-secret）
CREATE TABLE user_llm_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL UNIQUE COMMENT 'app_user.id，每用户一条生效配置',
    provider        VARCHAR(20)  NOT NULL COMMENT 'dashscope / openai（openai 表示 OpenAI 兼容协议）',
    api_key_enc     VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密后的 API key（Base64: IV+密文）',
    base_url        VARCHAR(255) DEFAULT NULL COMMENT 'provider=openai 时必填，如 https://api.deepseek.com/v1',
    model           VARCHAR(64)  NOT NULL COMMENT '用户手填的模型名，保存时经 test call 校验',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- llm_trace：key 来源（user 的行成本记 0）+ 调用方用户（支撑按用户用量统计）
ALTER TABLE llm_trace ADD COLUMN key_source VARCHAR(10) NOT NULL DEFAULT 'system'
    COMMENT 'system=系统key / user=用户自有key（estimated_cost 记 0）';
ALTER TABLE llm_trace ADD COLUMN user_id BIGINT DEFAULT NULL
    COMMENT '调用方用户ID（取自 BaseContext），支持按用户统计用量；存量数据为 NULL';
```

- [ ] **Step 3: 编译验证依赖可解析**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q
```

Expected: BUILD SUCCESS（无依赖解析错误）。

- [ ] **Step 4: Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; git add pom.xml src/main/resources/db/migration/V10__user_llm_config.sql; git commit -m "feat: add spring-ai-openai dep and V10 user_llm_config migration"
```

---

### Task 2: LlmTrace 实体 + LlmTraceMapper 适配

**Files:**
- Modify: `src/main/java/com/interview/agent/observability/LlmTrace.java`
- Modify: `src/main/java/com/interview/agent/observability/LlmTraceMapper.java`

- [ ] **Step 1: LlmTrace 加两个字段**（字段声明加在 `private String completionExcerpt;` 之后；getter/setter 加在 `getCreatedAt` 之前，保持现有风格）

```java
    /** system=系统key / user=用户自有key（estimatedCost 记 0） */
    private String keySource;
    /** 调用方用户ID（BaseContext），按用户用量统计 */
    private Long userId;
```

```java
    public String getKeySource() {
        return keySource;
    }

    public void setKeySource(String keySource) {
        this.keySource = keySource;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
```

- [ ] **Step 2: LlmTraceMapper.insert SQL 加两列**

原：
```java
    @Insert("INSERT INTO llm_trace(session_id, trace_id, agent, kind, model, prompt_tokens, completion_tokens, total_tokens, " +
            "duration_ms, status, error_msg, estimated_cost, prompt_excerpt, completion_excerpt) " +
            "VALUES(#{sessionId}, #{traceId}, #{agent}, #{kind}, #{model}, #{promptTokens}, #{completionTokens}, #{totalTokens}, " +
            "#{durationMs}, #{status}, #{errorMsg}, #{estimatedCost}, #{promptExcerpt}, #{completionExcerpt})")
```
改为：
```java
    @Insert("INSERT INTO llm_trace(session_id, trace_id, agent, kind, model, prompt_tokens, completion_tokens, total_tokens, " +
            "duration_ms, status, error_msg, estimated_cost, prompt_excerpt, completion_excerpt, key_source, user_id) " +
            "VALUES(#{sessionId}, #{traceId}, #{agent}, #{kind}, #{model}, #{promptTokens}, #{completionTokens}, #{totalTokens}, " +
            "#{durationMs}, #{status}, #{errorMsg}, #{estimatedCost}, #{promptExcerpt}, #{completionExcerpt}, #{keySource}, #{userId})")
```

- [ ] **Step 3: 成本口径改"仅系统 key"**（sessionSummaries 与 overallSummary 两处）

`sessionSummaries` 与 `overallSummary` 中各自的 `COALESCE(SUM(estimated_cost), 0) AS estimatedCost` 改为：
```java
            "COALESCE(SUM(CASE WHEN key_source = 'system' THEN estimated_cost ELSE 0 END), 0) AS estimatedCost, " +
```

- [ ] **Step 4: 新增按用户用量聚合查询**（加在 `agentSummary` 之后）

```java
    /** 按用户累计用量（仅 LLM 调用；含系统 key 时期的历史用量） */
    @Select("SELECT COUNT(*) AS totalCalls, " +
            "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(total_tokens), 0) AS totalTokens " +
            "FROM llm_trace WHERE user_id = #{userId} AND kind = 'llm'")
    Map<String, Object> sumByUserId(Long userId);
```

- [ ] **Step 5: 编译 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q; git add src/main/java/com/interview/agent/observability/LlmTrace.java src/main/java/com/interview/agent/observability/LlmTraceMapper.java; git commit -m "feat: llm_trace add key_source/user_id columns with mapper support"
```

---

### Task 3: LlmTraceContext 扩展 + ObservationHandler 落库

**Files:**
- Modify: `src/main/java/com/interview/agent/observability/LlmTraceContext.java`
- Modify: `src/main/java/com/interview/agent/observability/LlmTraceObservationHandler.java`
- Modify: `src/main/java/com/interview/agent/knowledge/KnowledgeRetriever.java`（retrieval span 补 userId）

- [ ] **Step 1: LlmTraceContext 加 userId/keySource 字段**（不可变值对象，保持现有风格）

字段区替换为：
```java
    private final String agent;
    private final String sessionId;
    /** 轮次关联 ID：同轮多次调用（出题/检索/评分/追问）共享，可为 null（图外调用） */
    private final String roundTraceId;
    /** 调用方用户ID（路由与用量统计归因），可为 null（系统内部调用） */
    private final Long userId;
    /** key 来源：system / user；null 视为 system */
    private final String keySource;
```

构造器区替换为（旧构造器保留兼容，委托全参构造器）：
```java
    public LlmTraceContext(String agent, String sessionId) {
        this(agent, sessionId, null, null, null);
    }

    public LlmTraceContext(String agent, String sessionId, String roundTraceId) {
        this(agent, sessionId, roundTraceId, null, null);
    }

    public LlmTraceContext(String agent, String sessionId, String roundTraceId, Long userId, String keySource) {
        this.agent = agent;
        this.sessionId = sessionId;
        this.roundTraceId = roundTraceId;
        this.userId = userId;
        this.keySource = keySource;
    }
```

getter 区加：
```java
    public Long getUserId() {
        return userId;
    }

    public String getKeySource() {
        return keySource;
    }
```

with* 方法区替换为（现有三个 with 方法补全字段传递，再加两个新方法）：
```java
    public LlmTraceContext withAgent(String newAgent) {
        return new LlmTraceContext(newAgent, this.sessionId, this.roundTraceId, this.userId, this.keySource);
    }

    public LlmTraceContext withSessionId(String newSessionId) {
        return new LlmTraceContext(this.agent, newSessionId, this.roundTraceId, this.userId, this.keySource);
    }

    public LlmTraceContext withRoundTraceId(String newRoundTraceId) {
        return new LlmTraceContext(this.agent, this.sessionId, newRoundTraceId, this.userId, this.keySource);
    }

    public LlmTraceContext withUserId(Long newUserId) {
        return new LlmTraceContext(this.agent, this.sessionId, this.roundTraceId, newUserId, this.keySource);
    }

    public LlmTraceContext withKeySource(String newKeySource) {
        return new LlmTraceContext(this.agent, this.sessionId, this.roundTraceId, this.userId, newKeySource);
    }
```

- [ ] **Step 2: LlmTraceObservationHandler.buildTrace 落 keySource/userId + 用户 key 成本记 0**

`buildTrace` 中 holderCtx 块替换为：
```java
        LlmTraceContext holderCtx = LlmTraceContextHolder.current();
        if (holderCtx != null) {
            trace.setAgent(holderCtx.getAgent());
            trace.setSessionId(holderCtx.getSessionId());
            trace.setTraceId(holderCtx.getRoundTraceId());
            trace.setUserId(holderCtx.getUserId());
            trace.setKeySource(holderCtx.getKeySource() != null ? holderCtx.getKeySource() : "system");
        } else {
            trace.setKeySource("system");
        }
        // 系统 key 路径兜底落 userId（路由代理会显式标记；此处覆盖未经路由标记的调用点）
        if (trace.getUserId() == null) {
            trace.setUserId(BaseContext.getCurrentId());
        }
```
文件头加 import：
```java
import com.interview.agent.common.context.BaseContext;
```

成本计算行 `trace.setEstimatedCost(computeCost(trace.getPromptTokens(), trace.getCompletionTokens()));` 替换为：
```java
        // 用户自有 key：token 照常记录，成本记 0（不计入部署方成本）
        if ("user".equals(trace.getKeySource())) {
            trace.setEstimatedCost(BigDecimal.ZERO);
        } else {
            trace.setEstimatedCost(computeCost(trace.getPromptTokens(), trace.getCompletionTokens()));
        }
```

- [ ] **Step 3: KnowledgeRetriever 的 retrieval span 补 userId**

在 `KnowledgeRetriever.java` 中手动构建 span 提交前的 `set...` 聚集处加一行（变量名以现场为准）：
```java
            span.setUserId(BaseContext.getCurrentId());
```
并确认 import `com.interview.agent.common.context.BaseContext`。

- [ ] **Step 4: 编译 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q; git add src/main/java/com/interview/agent/observability/ src/main/java/com/interview/agent/knowledge/KnowledgeRetriever.java; git commit -m "feat: trace context carries userId/keySource, user-key traces cost zero"
```

---

### Task 4: ApiKeyCipher（AES-GCM）+ 配置项 + 单测

**Files:**
- Create: `src/main/java/com/interview/agent/llm/ApiKeyCipher.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml.example`
- Test: `src/test/java/com/interview/agent/llm/ApiKeyCipherTest.java`

- [ ] **Step 1: 创建 ApiKeyCipher**

```java
package com.interview.agent.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户 LLM API Key 加解密（AES-GCM）。
 * 密钥来自配置 llm.key-cipher-secret（application-local.yml，不入库不硬编码），SHA-256 派生 32 字节 AES key。
 * 密文格式：Base64(12字节随机IV + 密文)。
 */
@Component
public class ApiKeyCipher {
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCipher(@Value("${llm.key-cipher-secret}") String secret) throws Exception {
        byte[] raw = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + enc.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(enc, 0, out, IV_LEN, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API key 加密失败", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            byte[] enc = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, enc, 0, enc.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API key 解密失败", e);
        }
    }
}
```

- [ ] **Step 2: application.yml 加配置项**（加在 `sandbox:` 块之前）

```yaml
llm:
  # 用户 API key 加密密钥（AES-GCM，SHA-256 派生）；生产/本地真实值放 application-local.yml
  key-cipher-secret: ${LLM_KEY_CIPHER_SECRET:change-me-key-cipher-secret}
```

- [ ] **Step 3: application-local.yml.example 加模板行**

在 `api-key: 你的-DashScope-API-Key` 块后追加：
```yaml
llm:
  # 用户自带 LLM key 的落库加密密钥（任意强随机字符串）
  key-cipher-secret: 你的-随机加密密钥
```

- [ ] **Step 4: 写单测**（纯工具类，不依赖 Spring 上下文）

```java
package com.interview.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyCipherTest {

    private ApiKeyCipher newCipher() throws Exception {
        return new ApiKeyCipher("test-secret-for-unit-test");
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        ApiKeyCipher cipher = newCipher();
        String plain = "sk-abc123XYZ-测试key";
        String enc = cipher.encrypt(plain);
        assertNotEquals(plain, enc);
        assertEquals(plain, cipher.decrypt(enc));
    }

    @Test
    void samePlainDifferentCiphertext() throws Exception {
        ApiKeyCipher cipher = newCipher();
        assertNotEquals(cipher.encrypt("sk-same"), cipher.encrypt("sk-same"));
    }

    @Test
    void wrongSecretFails() throws Exception {
        ApiKeyCipher c1 = newCipher();
        ApiKeyCipher c2 = new ApiKeyCipher("another-secret");
        assertThrows(Exception.class, () -> c2.decrypt(c1.encrypt("sk-x")));
    }
}
```

- [ ] **Step 5: 跑单测 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn test -Dtest=ApiKeyCipherTest -q
```
Expected: Tests run: 3, Failures: 0。

```powershell
git add src/main/java/com/interview/agent/llm/ApiKeyCipher.java src/test/java/com/interview/agent/llm/ApiKeyCipherTest.java src/main/resources/application.yml src/main/resources/application-local.yml.example; git commit -m "feat: AES-GCM cipher for user LLM api keys"
```

---

### Task 5: llm 包基础类（实体 / Mapper / 异常 / DTO）

**Files:**
- Create: `src/main/java/com/interview/agent/llm/UserLlmConfig.java`
- Create: `src/main/java/com/interview/agent/llm/UserLlmConfigMapper.java`
- Create: `src/main/java/com/interview/agent/llm/ResolvedLlmConfig.java`
- Create: `src/main/java/com/interview/agent/llm/LlmKeyNotConfiguredException.java`
- Create: `src/main/java/com/interview/agent/llm/UserLlmConfigSaveDTO.java`
- Create: `src/main/java/com/interview/agent/llm/UserLlmConfigVO.java`

- [ ] **Step 1: 创建实体 UserLlmConfig**

```java
package com.interview.agent.llm;

import java.time.LocalDateTime;

/** user_llm_config 表实体：每用户一条 LLM 配置（apiKeyEnc 为密文，绝不出 controller 层） */
public class UserLlmConfig {
    private Long id;
    private Long userId;
    /** dashscope / openai（OpenAI 兼容协议） */
    private String provider;
    private String apiKeyEnc;
    private String baseUrl;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: 创建 Mapper**（注解式，遵循 UserMapper 惯例）

```java
package com.interview.agent.llm;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserLlmConfigMapper {

    @Select("SELECT * FROM user_llm_config WHERE user_id = #{userId}")
    UserLlmConfig findByUserId(Long userId);

    /** upsert：user_id 唯一键冲突时更新（updated_at 由列的 ON UPDATE 自动维护） */
    @Insert("INSERT INTO user_llm_config(user_id, provider, api_key_enc, base_url, model) " +
            "VALUES(#{userId}, #{provider}, #{apiKeyEnc}, #{baseUrl}, #{model}) " +
            "ON DUPLICATE KEY UPDATE provider = VALUES(provider), api_key_enc = VALUES(api_key_enc), " +
            "base_url = VALUES(base_url), model = VALUES(model)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void upsert(UserLlmConfig config);

    @Delete("DELETE FROM user_llm_config WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);
}
```

- [ ] **Step 3: 创建 ResolvedLlmConfig**（含明文 key 的内部值对象，仅 service/路由层使用）

```java
package com.interview.agent.llm;

/**
 * 解密后的用户 LLM 配置（内部使用，禁止序列化出 controller / 禁止打日志）。
 */
public record ResolvedLlmConfig(String provider, String apiKey, String baseUrl, String model) {
    public static final String PROVIDER_DASHSCOPE = "dashscope";
    public static final String PROVIDER_OPENAI = "openai";
}
```

- [ ] **Step 4: 创建业务异常**

```java
package com.interview.agent.llm;

import com.interview.agent.common.exception.BaseException;

/**
 * 用户未配置 LLM API Key：code=1001，前端据此引导跳转 /settings/llm。
 */
public class LlmKeyNotConfiguredException extends BaseException {
    public static final int CODE = 1001;

    public LlmKeyNotConfiguredException() {
        super(CODE, "尚未配置 LLM API Key，请先在「模型设置」中配置自己的 Key");
    }
}
```

- [ ] **Step 5: 创建 DTO / VO**

```java
package com.interview.agent.llm;

/** 保存配置请求体 */
public class UserLlmConfigSaveDTO {
    /** dashscope / openai */
    private String provider;
    private String apiKey;
    private String baseUrl;
    private String model;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
```

```java
package com.interview.agent.llm;

import java.time.LocalDateTime;

/** 配置查询响应（key 脱敏） */
public class UserLlmConfigVO {
    private String provider;
    private String apiKeyMasked;
    private String baseUrl;
    private String model;
    private LocalDateTime updatedAt;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 6: 编译 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q; git add src/main/java/com/interview/agent/llm/; git commit -m "feat: user llm config entity, mapper, dto and exception"
```

---

### Task 6: UserChatModelFactory（per-user 模型构建 + 缓存）

**Files:**
- Create: `src/main/java/com/interview/agent/llm/UserChatModelFactory.java`

关键点（已 javap 验证签名）：
- 手动构建的模型必须传 `observationRegistry`，否则 llm_trace 不落库
- DashScope 侧保留 `enableThinking(true)` 硬约束
- 缓存键用 userId（每用户仅一条配置，变更时必 evict）

- [ ] **Step 1: 创建 UserChatModelFactory**

```java
package com.interview.agent.llm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户配置构建 per-user ChatModel，带缓存（键 = userId）。
 *
 * <p>注意：
 * <ul>
 *   <li>必须显式传 observationRegistry，否则用户 key 的调用不落 llm_trace</li>
 *   <li>DashScope 保留 enable-thinking=true（qwen3.7-max 混合思考模型的服务端硬约束）</li>
 *   <li>禁止在此类任何日志中输出明文 apiKey</li>
 * </ul>
 */
@Component
public class UserChatModelFactory {
    private static final Logger log = LoggerFactory.getLogger(UserChatModelFactory.class);

    private final ObservationRegistry observationRegistry;
    private final ConcurrentHashMap<Long, ChatModel> cache = new ConcurrentHashMap<>();

    public UserChatModelFactory(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    /** 取缓存或按配置构建（缓存未命中时） */
    public ChatModel getOrCreate(Long userId, ResolvedLlmConfig cfg) {
        return cache.computeIfAbsent(userId, id -> {
            log.info("为用户 {} 构建 LLM 客户端: provider={}, model={}", id, cfg.provider(), cfg.model());
            return build(cfg);
        });
    }

    /** 配置更新/删除后失效缓存 */
    public void evict(Long userId) {
        if (cache.remove(userId) != null) {
            log.info("用户 {} 的 LLM 客户端缓存已失效", userId);
        }
    }

    /** 构建全新实例（不缓存）：test call 与缓存构建共用 */
    public ChatModel build(ResolvedLlmConfig cfg) {
        if (ResolvedLlmConfig.PROVIDER_DASHSCOPE.equals(cfg.provider())) {
            DashScopeApi api = DashScopeApi.builder().apiKey(cfg.apiKey()).build();
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .model(cfg.model())
                    .enableThinking(true)
                    .build();
            return DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .observationRegistry(observationRegistry)
                    .build();
        }
        // OpenAI 兼容协议
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(cfg.baseUrl())
                .apiKey(cfg.apiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(cfg.model())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }
}
```

- [ ] **Step 2: 编译 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q; git add src/main/java/com/interview/agent/llm/UserChatModelFactory.java; git commit -m "feat: per-user chat model factory with cache"
```

---

### Task 7: UserRoutingChatModel（@Primary 路由代理）

**Files:**
- Create: `src/main/java/com/interview/agent/llm/UserRoutingChatModel.java`

关键点：
- `@Primary` 使 Spring AI 自动配置的 `ChatClient.Builder` 以本代理为底层模型，**所有现有调用点零改动**
- 自动配置的 `DashScopeChatModel` bean 仍存在（系统 key），按类型注入仅用于 `getDefaultOptions()` 委托
- userId 为 null 即抛 `LlmKeyNotConfiguredException`（强制配置，无内部豁免通道）
- 委托前在 `LlmTraceContextHolder` 标记 `userId/keySource=user`（同步 call 与 observation onStop 同线程可读到；stream 链路由 ChatService 手动落带归因的 trace，observation 行 token/cost 恒 0 不受影响）

- [ ] **Step 1: 创建 UserRoutingChatModel**

```java
package com.interview.agent.llm;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.interview.agent.common.context.BaseContext;
import com.interview.agent.observability.LlmTraceContext;
import com.interview.agent.observability.LlmTraceContextHolder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 用户级 LLM 路由代理（@Primary）：所有 ChatClient 的底层模型。
 *
 * <p>每次调用在 call()/stream() 入口按当前用户（BaseContext）解析真实目标模型并委托：
 * <ul>
 *   <li>用户已配置自有 key → 委托给 UserChatModelFactory 缓存的 per-user 模型，并标记 trace 归因（keySource=user）</li>
 *   <li>未配置 → 抛 LlmKeyNotConfiguredException（code=1001），前端引导到设置页</li>
 * </ul>
 *
 * <p>线程前提：调用线程必须持有 userId —— 面试/eval 链路已在执行线程设置 BaseContext；
 * LlmCallWrapper 与 ChatService.runAsk 的传播在 Task 9 补齐。
 */
@Component
@Primary
public class UserRoutingChatModel implements ChatModel {

    /** 自动配置的系统 DashScope 模型：仅借其 defaultOptions 供 ChatClient 初始化读取，不参与调用 */
    private final DashScopeChatModel systemChatModel;
    private final UserLlmConfigService configService;
    private final UserChatModelFactory factory;

    public UserRoutingChatModel(DashScopeChatModel systemChatModel,
                                UserLlmConfigService configService,
                                UserChatModelFactory factory) {
        this.systemChatModel = systemChatModel;
        this.configService = configService;
        this.factory = factory;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolve().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resolve().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return systemChatModel.getDefaultOptions();
    }

    private ChatModel resolve() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new LlmKeyNotConfiguredException();
        }
        ResolvedLlmConfig cfg = configService.resolveForUser(userId);
        if (cfg == null) {
            throw new LlmKeyNotConfiguredException();
        }
        // trace 归因标记：与当前上下文合并（保留 agent/sessionId/roundTraceId）
        LlmTraceContext ctx = LlmTraceContextHolder.current();
        ctx = ctx == null
                ? new LlmTraceContext(null, null, null, userId, "user")
                : ctx.withUserId(userId).withKeySource("user");
        LlmTraceContextHolder.set(ctx);
        return factory.getOrCreate(userId, cfg);
    }
}
```

- [ ] **Step 2: 编译（此时 UserLlmConfigService 尚未创建，预期报错）**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q
```
Expected: 编译失败，`找不到符号 UserLlmConfigService` —— Task 8 补齐后通过。此步仅确认错误来源唯一。

- [ ] **Step 3: 暂不 commit**（与 Task 8 一起提交，保持每次 commit 可编译）

---

### Task 8: UserLlmConfigService + UserLlmConfigController

**Files:**
- Create: `src/main/java/com/interview/agent/llm/UserLlmConfigService.java`
- Create: `src/main/java/com/interview/agent/llm/UserLlmConfigController.java`

关键点：
- 保存前必须 test call（真实 LLM 调用，15s 超时，独立线程池执行），通过才落库；失败抛 code=1002
- 落库/删除后必须 `factory.evict(userId)`，保证下次调用用新配置
- VO 永不返回明文 key（前 3 后 4 脱敏）
- userId 一律取 BaseContext，不接受前端传入；参数错误抛 BaseException（code=0 走通用错误提示）

- [ ] **Step 1: 创建 UserLlmConfigService**

```java
package com.interview.agent.llm;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.observability.LlmTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

/**
 * 用户 LLM 配置服务：查询 / 保存（含 test call 校验）/ 删除 / 用量。
 *
 * <p>安全约束：
 * <ul>
 *   <li>apiKey 明文只存在于 test call 与加密前的短暂窗口，禁止打日志；</li>
 *   <li>出 service 的配置一律经 {@link #mask(String)} 脱敏；</li>
 *   <li>userId 一律取 BaseContext（登录态），不接受前端传入。</li>
 * </ul>
 */
@Service
public class UserLlmConfigService {
    private static final Logger log = LoggerFactory.getLogger(UserLlmConfigService.class);

    /** LLM_KEY_INVALID：key 不可用或 key 与模型不匹配（test call 未通过） */
    public static final int CODE_LLM_KEY_INVALID = 1002;
    /** test call 超时（秒）：key 错误/模型不存在通常快速失败，15s 足够覆盖网络抖动 */
    private static final long TEST_CALL_TIMEOUT_SECONDS = 15;

    private final UserLlmConfigMapper mapper;
    private final ApiKeyCipher cipher;
    private final UserChatModelFactory factory;
    private final LlmTraceMapper traceMapper;

    /** test call 专用池：与业务线程隔离，避免配置校验阻塞面试/问答链路 */
    private final ExecutorService testCallExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-test-call");
        t.setDaemon(true);
        return t;
    });

    public UserLlmConfigService(UserLlmConfigMapper mapper, ApiKeyCipher cipher,
                                UserChatModelFactory factory, LlmTraceMapper traceMapper) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.factory = factory;
        this.traceMapper = traceMapper;
    }

    /** 路由代理用：解密后的生效配置；未配置返回 null。禁止打日志（含明文 key）。 */
    public ResolvedLlmConfig resolveForUser(Long userId) {
        UserLlmConfig cfg = mapper.findByUserId(userId);
        if (cfg == null) {
            return null;
        }
        return new ResolvedLlmConfig(cfg.getProvider(), cipher.decrypt(cfg.getApiKeyEnc()),
                cfg.getBaseUrl(), cfg.getModel());
    }

    public boolean isConfigured(Long userId) {
        return userId != null && mapper.findByUserId(userId) != null;
    }

    /** 当前用户的配置（key 脱敏）；未配置返回 null */
    public UserLlmConfigVO getForCurrentUser() {
        UserLlmConfig cfg = mapper.findByUserId(currentUserId());
        return cfg == null ? null : toVo(cfg);
    }

    /** 保存：参数校验 → test call 真实校验 → 加密落库 → 失效缓存 */
    public UserLlmConfigVO saveForCurrentUser(UserLlmConfigSaveDTO dto) {
        Long userId = currentUserId();
        ResolvedLlmConfig resolved = validateAndResolve(dto);
        testCall(resolved);

        UserLlmConfig cfg = new UserLlmConfig();
        cfg.setUserId(userId);
        cfg.setProvider(resolved.provider());
        cfg.setApiKeyEnc(cipher.encrypt(resolved.apiKey()));
        cfg.setBaseUrl(resolved.baseUrl());
        cfg.setModel(resolved.model());
        mapper.upsert(cfg);
        factory.evict(userId);
        log.info("用户 {} 的 LLM 配置已更新: provider={}, model={}", userId, resolved.provider(), resolved.model());
        return toVo(mapper.findByUserId(userId));
    }

    public void deleteForCurrentUser() {
        Long userId = currentUserId();
        mapper.deleteByUserId(userId);
        factory.evict(userId);
        log.info("用户 {} 的 LLM 配置已删除", userId);
    }

    /** 当前用户的累计 LLM 用量（token 维度；含历史系统 key 时期的用量） */
    public Map<String, Object> usageForCurrentUser() {
        return traceMapper.sumByUserId(currentUserId());
    }

    // ---------- 内部 ----------

    private Long currentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException(401, "未登录");
        }
        return userId;
    }

    private ResolvedLlmConfig validateAndResolve(UserLlmConfigSaveDTO dto) {
        if (dto == null || dto.getProvider() == null || dto.getProvider().isBlank()) {
            throw new BaseException("provider 不能为空（dashscope / openai）");
        }
        String provider = dto.getProvider().strip().toLowerCase();
        if (!ResolvedLlmConfig.PROVIDER_DASHSCOPE.equals(provider)
                && !ResolvedLlmConfig.PROVIDER_OPENAI.equals(provider)) {
            throw new BaseException("不支持的 provider: " + provider + "（仅支持 dashscope / openai）");
        }
        if (dto.getApiKey() == null || dto.getApiKey().isBlank()) {
            throw new BaseException("API Key 不能为空");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new BaseException("模型名不能为空");
        }
        String baseUrl = dto.getBaseUrl() == null ? null : dto.getBaseUrl().strip();
        if (ResolvedLlmConfig.PROVIDER_OPENAI.equals(provider)) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new BaseException("OpenAI 兼容协议必须填写 Base URL，如 https://api.deepseek.com/v1");
            }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new BaseException("Base URL 必须以 http(s):// 开头");
            }
        } else {
            // dashscope 走官方端点，忽略前端传入的 baseUrl
            baseUrl = null;
        }
        return new ResolvedLlmConfig(provider, dto.getApiKey().strip(), baseUrl, dto.getModel().strip());
    }

    /**
     * test call：用用户配置构建全新模型实例（不进缓存），发起一次真实 LLM 调用。
     * 任何失败（401 key 无效 / 404 模型不存在 / 超时）都视为校验不通过（code=1002），不落库。
     */
    private void testCall(ResolvedLlmConfig cfg) {
        ChatModel model = factory.build(cfg);
        Future<?> future = testCallExecutor.submit(
                () -> model.call(new Prompt("Reply with exactly one word: OK")));
        try {
            future.get(TEST_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BaseException(CODE_LLM_KEY_INVALID,
                    "API Key 校验超时（" + TEST_CALL_TIMEOUT_SECONDS + "s），请检查网络与 Base URL");
        } catch (ExecutionException e) {
            String cause = e.getCause() == null ? "" : String.valueOf(e.getCause().getMessage());
            log.info("用户 LLM 配置 test call 未通过: provider={}, model={}, cause={}",
                    cfg.provider(), cfg.model(), cause);
            throw new BaseException(CODE_LLM_KEY_INVALID, "API Key 校验失败，请检查 Key 与模型名是否匹配");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException(CODE_LLM_KEY_INVALID, "API Key 校验被中断，请重试");
        }
    }

    private UserLlmConfigVO toVo(UserLlmConfig cfg) {
        UserLlmConfigVO vo = new UserLlmConfigVO();
        vo.setProvider(cfg.getProvider());
        vo.setApiKeyMasked(mask(cipher.decrypt(cfg.getApiKeyEnc())));
        vo.setBaseUrl(cfg.getBaseUrl());
        vo.setModel(cfg.getModel());
        vo.setUpdatedAt(cfg.getUpdatedAt());
        return vo;
    }

    /** 前 3 后 4 脱敏；过短的 key 整体打码 */
    private String mask(String apiKey) {
        if (apiKey == null || apiKey.length() <= 7) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    @PreDestroy
    void shutdown() {
        testCallExecutor.shutdownNow();
    }
}
```

- [ ] **Step 2: 创建 UserLlmConfigController**

```java
package com.interview.agent.llm;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.result.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 用户 LLM 配置管理（全部操作作用于当前登录用户，userId 取自登录态） */
@RestController
@RequestMapping("/api/llm-config")
public class UserLlmConfigController {

    private final UserLlmConfigService service;

    public UserLlmConfigController(UserLlmConfigService service) {
        this.service = service;
    }

    /** 当前配置（key 脱敏）；未配置时 data=null */
    @GetMapping
    public Result<UserLlmConfigVO> get() {
        return Result.success(service.getForCurrentUser());
    }

    /** 轻量状态检查：面试开始页 / 问答页据此决定是否展示配置引导 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(Map.of("configured", service.isConfigured(BaseContext.getCurrentId())));
    }

    /** 保存（先经 test call 真实校验，通过才落库；失败返回 code=1002） */
    @PutMapping
    public Result<UserLlmConfigVO> save(@RequestBody UserLlmConfigSaveDTO dto) {
        return Result.success(service.saveForCurrentUser(dto));
    }

    @DeleteMapping
    public Result<Void> delete() {
        service.deleteForCurrentUser();
        return Result.success();
    }

    /** 我的 LLM 累计用量（token 维度：totalCalls / promptTokens / completionTokens / totalTokens） */
    @GetMapping("/usage")
    public Result<Map<String, Object>> usage() {
        return Result.success(service.usageForCurrentUser());
    }
}
```

- [ ] **Step 3: 编译 + 与 Task 7 一起 Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q; git add src/main/java/com/interview/agent/llm/; git commit -m "feat: user llm config service, controller and routing chat model"
```
Expected: BUILD SUCCESS（Task 7 的 UserLlmConfigService 缺失错误消失）。

---

### Task 9: ThreadLocal 传播补齐（LlmCallWrapper + ChatService）

**Files:**
- Modify: `src/main/java/com/interview/agent/common/ai/LlmCallWrapper.java`
- Modify: `src/main/java/com/interview/agent/chat/ChatService.java`

背景（已核实）：`InterviewService` 与 `EvalRunner` 已在异步执行线程设置 BaseContext，无需改；仅 `llm-call-*` 池（LlmCallWrapper）与 `rag-chat` 池（ChatService.runAsk）缺传播。不补则 `UserRoutingChatModel.resolve()` 拿不到 userId，对已配置用户也会误抛 1001。

- [ ] **Step 1: LlmCallWrapper 快照传播 BaseContext**

文件头加 import：
```java
import com.interview.agent.common.context.BaseContext;
```

`callWithRetry` 中 tracedCallable 构造块替换为：
```java
        final LlmTraceContext traceContext = snapshot;
        // 用户路由依赖调用线程的 userId：与 trace 上下文一并快照到执行线程
        final Long callerUserId = BaseContext.getCurrentId();
        Callable<T> tracedCallable = () -> {
            LlmTraceContextHolder.set(traceContext);
            BaseContext.setCurrentId(callerUserId);
            try {
                return callable.call();
            } finally {
                LlmTraceContextHolder.clear();
                BaseContext.removeCurrentId();
            }
        };
```

- [ ] **Step 2: ChatService 三处改动**

a) 文件头加 import：
```java
import com.interview.agent.common.context.BaseContext;
import com.interview.agent.llm.UserLlmConfigService;
```

b) 字段区加 `private final UserLlmConfigService userLlmConfigService;`，构造器加尾参并赋值：
```java
    public ChatService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                       KnowledgeBaseMapper kbMapper, KnowledgeRetriever retriever,
                       ChatClient.Builder chatClientBuilder, LlmTraceObservationHandler traceHandler,
                       ObjectMapper objectMapper, UserLlmConfigService userLlmConfigService) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.kbMapper = kbMapper;
        this.retriever = retriever;
        this.chatClient = chatClientBuilder.build();
        this.traceHandler = traceHandler;
        this.objectMapper = objectMapper;
        this.userLlmConfigService = userLlmConfigService;
    }
```

c) `runAsk` 传播登录态（rag-chat 线程原本没有 ThreadLocal）：
```java
    private void runAsk(Long sessionId, Long userId, String question, SseEmitter emitter) {
        // rag-chat 线程无登录态：显式传播，UserRoutingChatModel 路由依赖它
        BaseContext.setCurrentId(userId);
        try {
```
方法尾部 catch 块后补 finally：
```java
        } catch (Exception e) {
            log.error("chat ask 处理异常: sessionId={}", sessionId, e);
            send(emitter, "error", "处理失败，请稍后重试");
            safeComplete(emitter);
        } finally {
            BaseContext.removeCurrentId();
        }
    }
```

d) `recordLlmTrace` 补 userId / keySource 归因。签名改为：
```java
    private void recordLlmTrace(Long sessionId, Long userId, String prompt, String completion,
                                 long startNanos, boolean success, String errorMsg) {
```
`traceHandler.submit(trace);` 之前加：
```java
            trace.setUserId(userId);
            // 强制配置策略下走到 LLM 调用必为用户 key；未配置时路由已抛错，此处记 system（实际未发出调用）
            trace.setKeySource(userLlmConfigService.isConfigured(userId) ? "user" : "system");
```
两处调用点补传 userId（doOnError / doOnComplete 内，userId 为 runAsk 形参，lambda 可直接捕获）：
```java
recordLlmTrace(sessionId, userId, prompt, answer.toString(), startNanos, false, e.getMessage());
```
```java
recordLlmTrace(sessionId, userId, prompt, answer.toString(), startNanos, true, null);
```

- [ ] **Step 3: 编译 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn compile -q; git add src/main/java/com/interview/agent/common/ai/LlmCallWrapper.java src/main/java/com/interview/agent/chat/ChatService.java; git commit -m "feat: propagate BaseContext userId to llm-call and rag-chat threads"
```

---

### Task 10: 启动验证（独立库 + 端口 8082）

关键点：worktree 复制的 application-local.yml 默认指向主库 `ai_interview`。V10 迁移一旦应用到主库，主工作区旧代码再启动会因 flyway 版本校验失败（存在未知版本 10）。**自测使用独立库 `ai_interview_llm`，主库完全不受污染，主工作区后端可照常运行。**

- [ ] **Step 1: 确认依赖容器在运行**

```powershell
docker ps --filter name=ai-interview-mysql --filter name=ai-interview-redis --format "{{.Names}} {{.Status}}"
```
Expected: 两个容器均 Up。未运行则 `docker compose -f d:\IdeaProjects\ai-interview-agent\docker-compose.yml up -d`。

- [ ] **Step 2: 创建自测专用数据库**

```powershell
docker exec ai-interview-mysql mysql -uroot -proot123 -e "CREATE DATABASE IF NOT EXISTS ai_interview_llm CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

- [ ] **Step 3: 修改 worktree 的 application-local.yml**（该文件被 gitignore，改动不影响主工作区）

a) JDBC url 库名 `ai_interview` → `ai_interview_llm`（url 形如 `jdbc:mysql://localhost:13307/ai_interview?...`，只改库名段）；

b) 文件末尾追加加密密钥：
```yaml
llm:
  # 用户 LLM key 落库加密密钥（本地自测值，任意强随机串）
  key-cipher-secret: local-dev-key-cipher-secret-0f8d7c6b5a49
```

- [ ] **Step 4: 端口 8082 启动后端**（后台运行）

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```
Expected（日志）：Flyway 迁移 V10 成功（`Migrating schema ... to version "10 ..."`）；无 BeanCreationException；`Tomcat started on port 8082`。

- [ ] **Step 5: 验证 V10 迁移结构**

```powershell
docker exec ai-interview-mysql mysql -uroot -proot123 ai_interview_llm -e "SHOW TABLES LIKE 'user_llm_config'; SHOW COLUMNS FROM llm_trace LIKE 'key_source'; SHOW COLUMNS FROM llm_trace LIKE 'user_id';"
```
Expected: 三条查询各返回 1 行（表存在、两列存在）。

- [ ] **Step 6: 冒烟——未配置用户调用 LLM 功能返回 1001**

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8082/auth/login -ContentType 'application/json' -Body '{"username":"testuser","password":"test123456"}'
$headers = @{ Authorization = "Bearer $($login.data.accessToken)" }
Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/interviews/plan -Headers $headers -ContentType 'application/json' -Body '{}'
```
Expected: 返回体 `code = 1001`，msg 提示先配置 LLM Key（路由代理在未配置时抛 LlmKeyNotConfiguredException）。

> 说明：此步不 commit（仅运行验证）。若 Step 6 失败说明路由链路断，优先查 Task 9 的 ThreadLocal 传播是否遗漏。

---

### Task 11: 前端——api 封装 + 模型设置页 + 路由 + 主页入口

**Files:**
- Create: `frontend/src/api/llmConfig.ts`
- Create: `frontend/src/pages/LlmSettingsView.vue`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/pages/HomeView.vue`

- [ ] **Step 1: 创建 api/llmConfig.ts**

```ts
import request from '../utils/request';

export interface LlmConfig {
  provider: string;
  apiKeyMasked: string;
  baseUrl: string | null;
  model: string;
  updatedAt: string;
}

export interface LlmConfigSavePayload {
  provider: string;
  apiKey: string;
  baseUrl?: string;
  model: string;
}

export interface LlmUsage {
  totalCalls: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export const getLlmConfig = () => request.get('/api/llm-config');
export const getLlmConfigStatus = () => request.get('/api/llm-config/status');
export const saveLlmConfig = (data: LlmConfigSavePayload) => request.put('/api/llm-config', data);
export const deleteLlmConfig = () => request.delete('/api/llm-config');
export const getLlmUsage = () => request.get('/api/llm-config/usage');
```

- [ ] **Step 2: 创建模型设置页 LlmSettingsView.vue**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import {
  NAlert, NButton, NInput, NRadioGroup, NRadio, NSpin, NPopconfirm, useMessage
} from 'naive-ui';
import {
  getLlmConfig, saveLlmConfig, deleteLlmConfig, getLlmUsage,
  type LlmConfig, type LlmUsage
} from '../api/llmConfig';
import BackButton from '../components/BackButton.vue';

const route = useRoute();
const message = useMessage();

const loading = ref(true);
const saving = ref(false);
const current = ref<LlmConfig | null>(null);
const usage = ref<LlmUsage | null>(null);
/* 从面试页/问答页被引导过来时顶部展示原因提示 */
const reasonMissing = ref(route.query.reason === 'missing');

const provider = ref('dashscope');
const apiKey = ref('');
const baseUrl = ref('');
const model = ref('');

onMounted(async () => {
  await Promise.all([loadConfig(), loadUsage()]);
  loading.value = false;
});

async function loadConfig() {
  try {
    const res = await getLlmConfig();
    current.value = res.data.data || null;
    if (current.value) {
      provider.value = current.value.provider;
      baseUrl.value = current.value.baseUrl || '';
      model.value = current.value.model;
    }
  } catch { /* 未登录等异常由拦截器统一处理 */ }
}

async function loadUsage() {
  try {
    const res = await getLlmUsage();
    usage.value = res.data.data || null;
  } catch {}
}

async function handleSave() {
  if (!apiKey.value.trim()) { message.warning('请输入完整 API Key'); return; }
  if (!model.value.trim()) { message.warning('请输入模型名'); return; }
  if (provider.value === 'openai' && !baseUrl.value.trim()) { message.warning('请输入 Base URL'); return; }
  saving.value = true;
  try {
    const res = await saveLlmConfig({
      provider: provider.value,
      apiKey: apiKey.value.trim(),
      baseUrl: provider.value === 'openai' ? baseUrl.value.trim() : undefined,
      model: model.value.trim()
    });
    const body = res.data;
    if (body.code === 1) {
      message.success('校验通过，配置已保存');
      current.value = body.data;
      apiKey.value = '';
      reasonMissing.value = false;
      await loadUsage();
    } else {
      /* code=1002 等：test call 未通过，后端提示信息原样展示 */
      message.error(body.msg || '保存失败');
    }
  } catch (e: any) {
    message.error(e.response?.data?.msg || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function handleDelete() {
  try {
    await deleteLlmConfig();
    current.value = null;
    apiKey.value = '';
    message.success('配置已删除，LLM 功能将不可用直至重新配置');
  } catch {
    message.error('删除失败');
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <main class="max-w-2xl mx-auto px-4 py-8">
      <BackButton class="mb-4" />
      <h1 class="text-xl font-bold text-slate-800 mb-4">模型设置</h1>

      <n-alert v-if="reasonMissing" type="warning" class="mb-4" title="需要先配置 LLM API Key">
        当前账号尚未配置自己的 LLM Key，面试 / 知识问答 / 编程评估功能暂不可用。配置并校验通过后即可使用。
      </n-alert>

      <n-spin :show="loading">
        <!-- 当前配置 -->
        <div v-if="current" class="bg-white rounded-2xl shadow-card p-6 mb-6">
          <h2 class="font-semibold text-slate-800 mb-3">当前配置</h2>
          <div class="text-sm text-slate-600 space-y-1">
            <p>Provider：{{ current.provider === 'dashscope' ? '阿里云百炼 DashScope' : 'OpenAI 兼容' }}</p>
            <p>API Key：{{ current.apiKeyMasked }}</p>
            <p v-if="current.baseUrl">Base URL：{{ current.baseUrl }}</p>
            <p>模型：{{ current.model }}</p>
            <p class="text-slate-400">更新于 {{ current.updatedAt }}</p>
          </div>
          <n-popconfirm positive-text="确认删除" negative-text="取消" @positive-click="handleDelete">
            <template #trigger>
              <n-button size="small" type="error" secondary class="mt-4">删除配置</n-button>
            </template>
            删除后面试 / 问答 / 编程评估将不可用，确认删除？
          </n-popconfirm>
        </div>

        <!-- 编辑表单：每次保存都需重新输入完整 Key 并通过真实调用校验 -->
        <div class="bg-white rounded-2xl shadow-card p-6 mb-6">
          <h2 class="font-semibold text-slate-800 mb-4">{{ current ? '更新配置' : '配置 LLM API Key' }}</h2>
          <div class="space-y-4">
            <div>
              <label class="block text-sm text-slate-600 mb-1">Provider</label>
              <n-radio-group v-model:value="provider">
                <n-radio value="dashscope">阿里云百炼 DashScope</n-radio>
                <n-radio value="openai">OpenAI 兼容协议（自定义 Base URL）</n-radio>
              </n-radio-group>
            </div>
            <div>
              <label class="block text-sm text-slate-600 mb-1">API Key</label>
              <n-input v-model:value="apiKey" type="password" show-password-on="click"
                :placeholder="current ? '重新输入完整 Key（保存即整体替换）' : 'sk-...'" />
            </div>
            <div v-if="provider === 'openai'">
              <label class="block text-sm text-slate-600 mb-1">Base URL</label>
              <n-input v-model:value="baseUrl" placeholder="https://api.deepseek.com/v1" />
            </div>
            <div>
              <label class="block text-sm text-slate-600 mb-1">模型名</label>
              <n-input v-model:value="model" :placeholder="provider === 'dashscope' ? '如 qwen-plus' : '如 deepseek-chat'" />
            </div>
            <div class="flex items-center gap-3">
              <n-button type="primary" :loading="saving" @click="handleSave">校验并保存</n-button>
              <span class="text-xs text-slate-400">保存前会发起一次真实调用校验 Key 与模型名，约需几秒</span>
            </div>
          </div>
        </div>

        <!-- 我的用量 -->
        <div class="bg-white rounded-2xl shadow-card p-6">
          <h2 class="font-semibold text-slate-800 mb-4">我的用量（累计 Token）</h2>
          <div v-if="usage" class="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div class="text-center">
              <div class="text-2xl font-bold text-slate-800">{{ usage.totalCalls }}</div>
              <div class="text-xs text-slate-500 mt-1">调用次数</div>
            </div>
            <div class="text-center">
              <div class="text-2xl font-bold text-slate-800">{{ usage.promptTokens }}</div>
              <div class="text-xs text-slate-500 mt-1">输入 Tokens</div>
            </div>
            <div class="text-center">
              <div class="text-2xl font-bold text-slate-800">{{ usage.completionTokens }}</div>
              <div class="text-xs text-slate-500 mt-1">输出 Tokens</div>
            </div>
            <div class="text-center">
              <div class="text-2xl font-bold text-slate-800">{{ usage.totalTokens }}</div>
              <div class="text-xs text-slate-500 mt-1">总 Tokens</div>
            </div>
          </div>
          <p v-else class="text-sm text-slate-400">暂无用量数据</p>
        </div>
      </n-spin>
    </main>
  </div>
</template>
```

- [ ] **Step 3: router.ts 注册路由**

import 区（`import EvalView ...` 行后）加：
```ts
import LlmSettingsView from './pages/LlmSettingsView.vue';
```
路由数组 eval 行后加：
```ts
        { path: '/settings/llm', name: 'LlmSettings', component: LlmSettingsView, meta: { requiresAuth: true } },
```

- [ ] **Step 4: HomeView 加入口**

a) 顶部导航“Agent 评测”链接后追加：
```html
          <router-link to="/settings/llm"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">模型设置</router-link>
```

b) 快捷卡片 grid 列数 `lg:grid-cols-7` → `lg:grid-cols-8`，eval 卡片后追加第 8 个：
```html
        <router-link to="/settings/llm"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-sky-50 text-sky-600 flex items-center justify-center text-lg font-bold mb-3">钥</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-sky-600 transition-colors duration-200">模型设置</h3>
          <p class="text-sm text-slate-500 mt-1">配置自己的 LLM Key，查看 token 用量</p>
        </router-link>
```

- [ ] **Step 5: 前端编译检查 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config\frontend; npx vue-tsc --noEmit -p tsconfig.app.json
```
Expected: 无类型错误（若 vue-tsc 不可用则 `npm run build` 须成功）。

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; git add frontend/src/api/llmConfig.ts frontend/src/pages/LlmSettingsView.vue frontend/src/router.ts frontend/src/pages/HomeView.vue; git commit -m "feat: llm settings page with usage stats and home entry"
```

---

### Task 12: 前端引导拦截（面试开始页 / 问答页 / 全局兜底）

**Files:**
- Modify: `frontend/src/pages/InterviewStartView.vue`
- Modify: `frontend/src/pages/ChatView.vue`
- Modify: `frontend/src/utils/request.ts`

- [ ] **Step 1: InterviewStartView——进入面试页即检查配置状态**

a) script import 区加：
```ts
import { getLlmConfigStatus } from '../api/llmConfig';
```

b) 状态声明（`const error = ref('');` 附近）加 `const llmConfigured = ref(true);`，onMounted 改为：
```ts
onMounted(async () => {
  /* 面试开始前强制检查 LLM Key：未配置则禁用生成按钮并展示引导 */
  try {
    const st = await getLlmConfigStatus();
    llmConfigured.value = !!st.data.data?.configured;
  } catch { /* 网络异常不阻断页面，提交时由后端 1001 兜底 */ }
  try {
    const [res, jdRes] = await Promise.all([getResumes(), getJds()]);
    resumes.value = res.data.data;
    jds.value = jdRes.data.data;
  } catch {}
});
```

c) 模板：在 `<n-alert v-if="error" type="error" ...>` 行之前插入：
```html
        <n-alert v-if="!llmConfigured" type="warning" :bordered="false" class="rounded-lg" title="尚未配置 LLM API Key">
          面试功能需要调用你自己的 LLM Key，请先完成配置（保存时会自动发起一次真实调用校验）。
          <template #action>
            <n-button size="small" type="warning" @click="router.push('/settings/llm?reason=missing')">去配置</n-button>
          </template>
        </n-alert>
```

d) 生成计划按钮加禁用（原行 `<n-button type="primary" size="large" :loading="loading" @click="handleGeneratePlan">`）：
```html
          <n-button type="primary" size="large" :loading="loading" :disabled="!llmConfigured" @click="handleGeneratePlan">
```

- [ ] **Step 2: ChatView——未配置时顶部横幅**

a) naive-ui import 行加入 `NAlert`（现为 `import { NButton, NInput, NEmpty, NSpin, useMessage, useDialog } from 'naive-ui';`），并加：
```ts
import { getLlmConfigStatus } from '../api/llmConfig';
```

b) 状态 + onMounted（现为 `onMounted(async () => { await loadSessions(); });`）：
```ts
const llmConfigured = ref(true);

onMounted(async () => {
  try {
    const st = await getLlmConfigStatus();
    llmConfigured.value = !!st.data.data?.configured;
  } catch { /* 忽略，默认不打扰 */ }
  await loadSessions();
});
```

c) 模板：标题块（`<div class="flex items-center justify-between mb-6">…</div>`）之后、主内容 `<div class="flex gap-4 items-stretch">` 之前插入：
```html
      <n-alert v-if="!llmConfigured" type="warning" class="mb-4 rounded-xl" title="尚未配置 LLM API Key">
        知识问答需要调用你自己的 LLM Key，配置后方可使用。
        <template #action>
          <router-link to="/settings/llm?reason=missing" class="text-amber-600 font-medium hover:underline">去配置</router-link>
        </template>
      </n-alert>
```

- [ ] **Step 3: request.ts 全局兜底拦截 code=1001**

后端 BaseException 经 GlobalExceptionHandler 返回 HTTP 200 + body 错误码，因此拦截写在成功分支。响应拦截器成功回调改为：
```ts
request.interceptors.response.use(
  (response: AxiosResponse) => {
    // 业务码 1001 = 未配置 LLM Key：全局兜底引导到模型设置页（已在设置页时不重复跳转）
    if (response.data?.code === 1001 && !window.location.pathname.startsWith('/settings/llm')) {
      window.location.href = '/settings/llm?reason=missing';
    }
    return response;
  },
  async (error) => {
```
（error 分支保持不变。用 `window.location.href` 与现有 401 跳转风格一致，避免循环依赖 router。）

- [ ] **Step 4: 前端编译检查 + Commit**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config\frontend; npx vue-tsc --noEmit -p tsconfig.app.json
cd d:\IdeaProjects\ai-interview-agent-llm-config; git add frontend/src/pages/InterviewStartView.vue frontend/src/pages/ChatView.vue frontend/src/utils/request.ts; git commit -m "feat: guide unconfigured users to llm settings page"
```

---

### Task 13: e2e 验证脚本 verify-llm-config.ps1

**Files:**
- Create: `verify-llm-config.ps1`（worktree 根目录，与现有 verify-*.ps1 惯例一致）

脚本要点：
- 纯 ASCII 输出（避免 PowerShell 中文乱码）
- 复用主工作区的 DashScope key 作为"用户 key"（真实走一遍 test call 与面试 plan）
- 库操作目标为自测库 `ai_interview_llm`（不碰主库）
- 最后恢复配置，保持环境可用

- [ ] **Step 1: 创建脚本**

```powershell
# e2e: user LLM key config (feature/user-llm-config)
# Requires: worktree backend on :8082, mysql container ai-interview-mysql, db ai_interview_llm
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8082'
$failures = @()

function Check($name, $cond) {
  if ($cond) { Write-Host "[PASS] $name" -ForegroundColor Green }
  else { Write-Host "[FAIL] $name" -ForegroundColor Red; $script:failures += $name }
}

# 1. login as testuser
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' -Body '{"username":"testuser","password":"test123456"}'
$token = $login.data.accessToken
Check 'login' ($token.Length -gt 0)
$H = @{ Authorization = "Bearer $token" }

# 2. reuse system dashscope key as the user key; pick first non-embedding model from application.yml
$local = Get-Content "$PSScriptRoot\src\main\resources\application-local.yml" -Raw
$apiKey = ([regex]'(?m)^\s*api-key:\s*(\S+)').Match($local).Groups[1].Value
$appYml = Get-Content "$PSScriptRoot\src\main\resources\application.yml" -Raw
$model = ([regex]::Matches($appYml, '(?m)^\s*model:\s*(\S+)') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -notmatch 'embedding' } | Select-Object -First 1)
Check 'resolve key/model' ([bool]$apiKey -and [bool]$model)
Write-Host "using model: $model"
$planBody = '{"persona":"neutral","durationMinutes":30}'

# 3. clean slate
try { Invoke-RestMethod -Method Delete -Uri "$base/api/llm-config" -Headers $H | Out-Null } catch {}

# 4. status should be unconfigured
$st = Invoke-RestMethod -Uri "$base/api/llm-config/status" -Headers $H
Check 'status unconfigured' ($st.data.configured -eq $false)

# 5. llm call without config -> 1001
$r = Invoke-RestMethod -Method Post -Uri "$base/api/interviews/plan" -Headers $H -ContentType 'application/json' -Body $planBody
Check 'plan blocked with 1001' ($r.code -eq 1001)

# 6. save config (server runs a real test call before persisting)
$body = @{ provider = 'dashscope'; apiKey = $apiKey; model = $model } | ConvertTo-Json
$r = Invoke-RestMethod -Method Put -Uri "$base/api/llm-config" -Headers $H -ContentType 'application/json' -Body $body -TimeoutSec 60
Check 'save config (test call passed)' ($r.code -eq 1)

# 7. returned key must be masked, never plaintext
$cfg = Invoke-RestMethod -Uri "$base/api/llm-config" -Headers $H
Check 'key masked' ($cfg.data.apiKeyMasked -and ($cfg.data.apiKeyMasked -notlike "*$apiKey*"))

# 8. plan with user key -> success (real llm call)
$r = Invoke-RestMethod -Method Post -Uri "$base/api/interviews/plan" -Headers $H -ContentType 'application/json' -Body $planBody -TimeoutSec 180
Check 'plan success with user key' ($r.code -eq 1)

# 9. trace attribution: newest llm trace rows should be key_source=user, cost=0, user_id set
Start-Sleep -Seconds 2
$raw = docker exec ai-interview-mysql mysql -uroot -proot123 ai_interview_llm -N -e "SELECT key_source, IFNULL(user_id,-1), estimated_cost FROM llm_trace ORDER BY id DESC LIMIT 3;"
$rows = ($raw | Out-String)
Write-Host "latest traces: $rows"
Check 'trace key_source=user' ($rows -match 'user')
Check 'trace cost zero' ($rows -match '0\.0')
Check 'trace user_id set' ($rows -notmatch 'user\s+-1')

# 10. usage endpoint aggregates by user
$u = Invoke-RestMethod -Uri "$base/api/llm-config/usage" -Headers $H
Check 'usage totalCalls>=1' ($u.data.totalCalls -ge 1)

# 11. delete -> blocked again
Invoke-RestMethod -Method Delete -Uri "$base/api/llm-config" -Headers $H | Out-Null
$r = Invoke-RestMethod -Method Post -Uri "$base/api/interviews/plan" -Headers $H -ContentType 'application/json' -Body $planBody
Check 'blocked after delete (1001)' ($r.code -eq 1001)

# 12. restore config for continued use
$r = Invoke-RestMethod -Method Put -Uri "$base/api/llm-config" -Headers $H -ContentType 'application/json' -Body $body -TimeoutSec 60
Check 'restore config' ($r.code -eq 1)

if ($failures.Count -gt 0) { Write-Host "FAILED: $($failures -join ', ')" -ForegroundColor Red; exit 1 }
Write-Host "ALL PASS" -ForegroundColor Green
```

- [ ] **Step 2: 运行脚本**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; powershell -ExecutionPolicy Bypass -File .\verify-llm-config.ps1
```
Expected: 全部 `[PASS]`，结尾 `ALL PASS`，退出码 0。

- [ ] **Step 3: Commit**

```powershell
git add verify-llm-config.ps1; git commit -m "test: e2e script for user llm key config flow"
```

---

### Task 14: 文档更新 + 停在合并前（不 merge）

**Files:**
- Modify: `docs/reference/03-功能模块实现详解.md`（加“用户 LLM 配置（BYOK）”小节：路由代理、强制配置、test call、加密落库）
- Modify: `docs/reference/04-数据模型与API清单.md`（user_llm_config 表、llm_trace 两列、4 个 `/api/llm-config` 端点、错误码 1001/1002）
- Modify: `docs/reference/06-设计决策运行指南与未来方向.md`（决策记录：BYOK 强制配置、用户 key 成本记 0、AES-GCM 密钥管理）

- [ ] **Step 1: 按上述要点更新三份文档**（保持现有文档风格与语言）

- [ ] **Step 2: Commit 文档**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; git add docs/reference/; git commit -m "docs: user llm config (BYOK) module, schema and api reference"
```

- [ ] **Step 3: 收尾——停在合并前（用户明确要求）**

```powershell
cd d:\IdeaProjects\ai-interview-agent-llm-config; git log --oneline master..HEAD; git status --short
```

执行者必须遵守：
- 所有 commit 停留在 `feature/user-llm-config` 分支，**不执行 merge、不删 worktree、不删分支**；
- 最终向用户汇报：分支 commit 清单、e2e `ALL PASS` 证据、已知遗留问题；
- 等待用户明确指示后再进行合并操作。

---

## Self-Review

- Spec coverage：V10 迁移（T1）/ 加密（T4）/ 路由代理（T6-7）/ 保存校验（T8）/ ThreadLocal 传播（T9）/ 用量统计（T2+T8 usage）/ 设置页（T11）/ 三处引导+兜底（T12）/ e2e（T13）全覆盖。
- Placeholder scan：无 TBD；所有代码片段完整可粘贴。
- Type consistency：错误码 int（1001/1002）与 Result/BaseException 体系一致；`LlmTraceContextHolder.clear()`、`BaseContext.removeCurrentId()`、`Result.success(...)` 均与现有代码核对过。
- 已知边界：Chat SSE 链路的 1001 不走 request.ts 拦截（SSE 非 axios 响应），由问答页横幅承接；观测台成本口径改为仅系统 key，用户 key 行 token 照常记录、cost=0。
