# ai-interview-agent 实施计划（Phase 1a → 1b → 1c）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零构建独立仓库 `ai-interview-agent` —— 基于 Spring AI Alibaba StateGraph 的多 Agent 面试官面板 + Live Coding 评估 + 人格行为策略 + 长期记忆追踪（依据 `C:\Users\17485\Desktop\沉淀\AI面试\technical-design.md`）。

**Architecture:** 单模块 Spring Boot 应用 + 独立 Vue3/Vite 前端。面试核心为 StateGraph 编排（Coordinator 条件路由 → Technical/Project/Coding Agent 出题 → 评估 → 行为策略分支 → 循环），Checkpoint 持久化到 MySQL（MysqlSaver，已验证），Live Coding 通过官方 interruptBefore/resume 机制挂起等待。分三个可独立交付的 Phase，每阶段结束有 go/no-go 决策点。

**Tech Stack:** Java 21 + Spring Boot 3.5.8 + Spring AI Alibaba 1.1.2.0（graph-core 1.1.2.0）+ Maven + MySQL 8 + Redis 7 + Apache Tika 2.9 + Vue 3 + Vite + TypeScript + Monaco Editor + Tailwind CSS 4 + Docker 沙箱

---

## 0. 决策记录（已确认）

| 决策点 | 选择 | 理由 |
|:---|:---|:---|
| 仓库位置 | `d:\IdeaProjects\ai-interview-agent`（与 ThinkVerse 同级，独立 git 仓库） | 干净叙事 |
| 数据库 | MySQL 8（独立容器，端口 13307） | MysqlSaver 已验证 300+ checkpoint 落库；PostgreSQL 非差异点 |
| 构建工具 | Maven | 与验证环境一致，依赖已缓存 |
| 认证 | 自定义 JWT 双 Token 拦截器（移植 ThinkVerse） | 轻量、已验证 |
| 可观测性 | 暂不接入 Langfuse | 用户决策，后续可加 |
| 模型分工 | qwen-plus（出题/评估/计划）、qwen-turbo（Coordinator 路由） | 成本控制（文档 4.5 节） |
| 简历解析 | Apache Tika（PDF/DOCX/DOC/TXT → 纯文本），不做结构化提取 | 文档 2.2 决策 |
| 前端 | Vue 3 + Vite + TS + Tailwind 4 + Monaco | 按文档 5.2；纯应用无 SEO 需求，不引入 Nuxt |

**环境基线**（已验证）：本地 Docker Desktop + `thinkverse-mysql` 容器（13306）运行中；DashScope Key 位于 `ThinkVerse\thinkverse-server\src\main\resources\application-local.yml`（新项目独立配置，不入库）；JDK 25 运行 Java 21 编译目标无兼容问题。

## 1. 仓库结构（与 technical-design.md 第六章对齐，Maven 单模块）

```
ai-interview-agent/
├── pom.xml                            # Boot 3.5.8 parent + SAA 1.1.2.0（参考 ThinkVerse/probe/pom.xml）
├── docker-compose.yml                 # mysql:8.0(13307) + redis:7-alpine(6380)
├── .env.example                       # DASHSCOPE_API_KEY / JWT_SECRET / DB 凭据
├── src/main/java/com/interview/agent/
│   ├── InterviewAgentApplication.java
│   ├── common/
│   │   ├── result/                    # Result<T>, PageResult<T>（移植）
│   │   ├── exception/                 # BaseException + GlobalExceptionHandler（移植）
│   │   ├── config/                    # CORS / Jackson / ChatClient
│   │   ├── interceptor/               # JwtTokenInterceptor（移植）
│   │   └── context/                   # BaseContext（移植）
│   ├── auth/                          # 注册/登录/刷新（移植 ThinkVerse user 模块）
│   ├── resume/                        # 上传 + Tika 解析 + CRUD
│   ├── jd/                            # JD 文本 CRUD（可选模块）
│   ├── interview/
│   │   ├── controller/                # plan / start / answer / end / sessions / report
│   │   ├── plan/                      # PlanGenerator（InterviewPlan .entity()）
│   │   ├── agent/                     # CoordinatorAgent + Technical/Project/Coding/Speaker + AskQuestionTool
│   │   ├── graph/                     # InterviewState + InterviewGraph + node/（基于 probe/ 骨架）
│   │   ├── policy/                    # BehaviorPolicy + Pressure/Gentle/Neutral（Phase 1b）
│   │   ├── evaluation/                # EvaluationEngine + LlmCallWrapper（降级兜底）
│   │   └── report/                    # ReportGenerator
│   ├── coding/                        # 沙箱 + 测试用例（Phase 1c）
│   ├── memory/                        # KnowledgePoint + 归一化（Phase 1b）
│   └── sse/                           # SseRegistry（移植）
├── src/main/resources/
│   ├── application.yml                # 环境变量占位，本地用 application-local.yml（gitignore）
│   ├── db/migration/                  # Flyway V1__init_schema.sql ...
│   └── prompts/                       # 计划/出题/评估/追问提示词模板
└── frontend/
    ├── src/
    │   ├── api/                       # auth / resume / jd / interview / coding
    │   ├── components/                # MarkdownRenderer / ChatBubble / ScoreBadge / CodeEditor(Monaco)
    │   ├── pages/                     # Login / Register / Resumes / InterviewStart / InterviewRoom / Report
    │   ├── utils/sse.ts               # POST SSE 解析器（移植 ThinkVerse 前端）
    │   ├── stores/                    # Pinia: auth / interview
    │   └── App.vue + main.ts + router.ts
    ├── package.json / vite.config.ts / tailwind.config.ts
    └── .env
```

## 2. 数据库设计（MySQL，Flyway 管理）

按 technical-design.md 第七章，两张 Checkpoint 表由 MysqlSaver 自动创建（`GRAPH_CHECKPOINT` / `GRAPH_THREAD`，已验证），无需手写。Flyway 迁移文件：

- `V1__init_schema.sql`：`app_user`、`resume`、`job_description`、`interview_session`、`interview_round`
- `V2__knowledge_point.sql`（Phase 1b）：`knowledge_point`（含 verified 字段）
- `V3__coding_submission.sql`（Phase 1c）：`coding_submission`

关键表（与文档第 7 章一致，仅删去图数据库/冗余）：
- `app_user(id, username UNIQUE, email, password_hash, role, status, created_at, updated_at)`
- `resume(id, user_id, file_name, file_type, file_size, raw_text, content_hash, created_at, updated_at)`
- `job_description(id, user_id, title, raw_text, source_url, created_at, updated_at)`
- `interview_session(id VARCHAR(36) PK, user_id, resume_id, jd_id, direction, persona, duration_minutes, status[planned/in_progress/waiting_code/completed/interrupted/cancelled], interview_plan JSON, overall_score, report JSON, started_at, completed_at, created_at, updated_at)`
- `interview_round(id, session_id, round_number, agent_name, topic, question, candidate_answer, evaluation JSON, is_followup, followup_target, created_at)`
- `knowledge_point(id, user_id, topic, status[mastered/weak/untested], confidence, last_assessed, assessment_count, verified, created_at, updated_at, UNIQUE(user_id, topic))`

## 3. Phase 1a：单 Agent 文字面试 MVP（2-3 周）

> 目标：跑通"注册 → 传简历 → 生成计划 → StateGraph 文字问答 10-15 轮 → 结构化评估 → 报告"全流程。验收标准见 3.9。

### Task 1a-1: 仓库初始化 + Maven 骨架

**Files:**
- Create: `pom.xml`、`.gitignore`、`src/main/java/com/interview/agent/InterviewAgentApplication.java`、`src/main/resources/application.yml`

- [ ] **Step 1: 初始化 git 仓库与目录**

```bash
mkdir d:\IdeaProjects\ai-interview-agent && cd d:\IdeaProjects\ai-interview-agent
git init -b master
mkdir -p src/main/java/com/interview/agent src/main/resources/db/migration frontend
```

- [ ] **Step 2: 编写 pom.xml**（直接复制 `ThinkVerse\probe\pom.xml`，groupId 改为 `com.interview.agent`，artifactId 改为 `ai-interview-agent`，仅保留 spring-boot-starter-jdbc/mysql 运行时依赖，后续按需加 starter-web/validation）

- [ ] **Step 3: 编写 application.yml**

```yaml
spring:
  application:
    name: ai-interview-agent
  datasource:
    url: jdbc:mysql://localhost:13307/ai_interview?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD:root123}
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
      chat:
        options:
          model: qwen-plus
```

- [ ] **Step 4: 编译验证 + 提交**

```bash
mvn -q compile
# 期望：BUILD SUCCESS
git add . && git commit -m "chore: 项目骨架（Boot 3.5.8 + SAA 1.1.2.0）"
```

### Task 1a-2: 基础设施（docker-compose + 建库）

**Files:**
- Create: `docker-compose.yml`、`.env.example`、`.gitignore`（含 `application-local.yml`、`target/`、`node_modules/`）

- [ ] **Step 1: 编写 docker-compose.yml**（MySQL 13307 + Redis 6380，凭据 root/root123，库 `ai_interview`；避开 ThinkVerse 的 13306/6379）

- [ ] **Step 2: 启动并建库**

```bash
docker compose up -d
docker exec ai-interview-mysql mysql -uroot -proot123 -e "CREATE DATABASE IF NOT EXISTS ai_interview DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

- [ ] **Step 3: 提交**

### Task 1a-3: Flyway 建表 + 统一响应/异常

**Files:**
- Create: `src/main/resources/db/migration/V1__init_schema.sql`、`common/result/Result.java`、`common/result/PageResult.java`、`common/exception/BaseException.java`、`common/exception/GlobalExceptionHandler.java`
- 参考：`ThinkVerse\thinkverse-common\src\main\java\com\thinkverse\common\result\Result.java`（直接移植，改包名）

- [ ] **Step 1: 写 V1__init_schema.sql**（第 2 节表结构，按文档 7 章 DDL 改写为 MySQL 语法：`BIGSERIAL`→`BIGINT AUTO_INCREMENT`，`JSONB`→`JSON`，`TIMESTAMP DEFAULT NOW()` 保留）
- [ ] **Step 2: 移植 Result/PageResult/异常体系**
- [ ] **Step 3: 编译 + 启动验证 Flyway 建表**

```bash
mvn spring-boot:run
# 期望：启动日志出现 "Successfully applied 1 migration"，docker exec 查表存在
```

- [ ] **Step 4: 提交** `chore: 建表迁移 + 统一响应/异常体系`

### Task 1a-4: JWT 认证（移植 ThinkVerse）

**Files:**
- Create: `common/utils/JwtUtil.java`、`common/context/BaseContext.java`、`common/interceptor/JwtTokenInterceptor.java`、`common/config/WebMvcConfig.java`、`auth/AuthController.java`、`auth/AuthService.java`、`auth/UserMapper.java`、`auth/User.java`、`auth/UserRegisterDTO.java`、`auth/UserLoginDTO.java`、`auth/LoginVO.java`
- 参考：ThinkVerse `JwtUtil` / `JwtTokenInterceptor` / `BaseContext` / `AuthController`（直接移植改包名；依赖 `jjwt 0.12.6` 需加入 pom）

- [ ] **Step 1: 移植 JwtUtil + BaseContext + JwtTokenInterceptor**（双 Token：access 2h / refresh 7d，`/auth/login` 返回双 token，`/auth/refresh` 续期）
- [ ] **Step 2: 注册/登录/刷新 API + user 表 CRUD（Mapper 用 MyBatis-Plus 或手写 Mapper——决策：手写 Mapper + MyBatis，与 ThinkVerse 一致）**
- [ ] **Step 3: 单测（JUnit5 + Mockito）**：密码 BCrypt 校验、token 解析、过期拒绝
- [ ] **Step 4: 手动验证**：curl 注册 → 登录 → 带 token 访问 `/auth/me` → 刷新 token
- [ ] **Step 5: 提交** `feat(auth): JWT 双 Token 认证`

### Task 1a-5: 前端骨架（Vite + Vue3 + TS + Tailwind）

**Files:**
- Create: `frontend/`（`npm create vite@latest frontend -- --template vue-ts`）
- Modify: `frontend/src/App.vue`、`router.ts`、`views/LoginView.vue`、`views/RegisterView.vue`、`api/auth.ts`、`stores/auth.ts`、`utils/request.ts`（axios 拦截器，参考 ThinkVerse `frontend/utils/request.ts`）

- [ ] **Step 1: 初始化 Vite 项目 + 安装依赖**（vue-router、pinia、axios、tailwindcss、naive-ui 或 element-plus——决策：用 Naive UI，与 ThinkVerse 一致减少学习成本）

```bash
cd frontend && npm create vite@latest . -- --template vue-ts
npm i vue-router pinia axios naive-ui
npm i -D tailwindcss @tailwindcss/vite
```

- [ ] **Step 2: 路由 + 登录/注册页 + auth store + axios 拦截器（401 自动刷新 token）**
- [ ] **Step 3: 联调**：`npm run dev` 完成注册登录全流程（后端 8080，前端 5173，CORS 放行）
- [ ] **Step 4: 提交** `feat(frontend): 登录注册 + 请求拦截器`

### Task 1a-6: 简历模块（Tika 解析）

**Files:**
- Create: `resume/ResumeController.java`、`ResumeService.java`、`ResumeMapper.java`、`Resume.java`、`parser/TikaTextParser.java`、`ResumeUploadVO.java`
- pom 增加：`org.apache.tika:tika-core:2.9.0` + `tika-parsers-standard-package:2.9.0`、`spring-boot-starter-web`、`spring-boot-starter-validation`

- [ ] **Step 1: 实现 TikaTextParser**

```java
@Component
public class TikaTextParser {
    private final Tika tika = new Tika();
    /** PDF/DOCX/DOC/TXT → 纯文本 */
    public String parse(InputStream in, String fileName) throws Exception {
        return tika.parseToString(in);
    }
}
```

- [ ] **Step 2: CRUD API**：`POST /api/resumes/upload`（multipart，校验类型/大小 ≤10MB，存 raw_text + content_hash）、`GET /api/resumes`、`GET /api/resumes/{id}`、`DELETE /api/resumes/{id}`（权限校验 user_id）
- [ ] **Step 3: 单测**：用 1 份 PDF + 1 份 TXT 测试解析非空、大小限制、类型拒绝
- [ ] **Step 4: 前端简历管理页**（上传/列表/删除/预览原文）
- [ ] **Step 5: 提交** `feat(resume): Tika 简历解析与 CRUD`

### Task 1a-7: JD 模块（可选）

**Files:**
- Create: `jd/JdController.java`、`JdService.java`、`Jd.java`、`JdMapper.java`、`JdCreateDTO.java`

- [ ] **Step 1: CRUD API**：`POST /api/jds`（title + raw_text）、`GET /api/jds`、`GET /api/jds/{id}`、`DELETE /api/jds/{id}`
- [ ] **Step 2: 前端 JD 管理页**（简单表单 + 列表）
- [ ] **Step 3: 提交** `feat(jd): JD 管理`

### Task 1a-8: 面试计划引擎

**Files:**
- Create: `interview/plan/InterviewPlan.java`（record）、`interview/plan/PlanGenerator.java`、`interview/plan/PlanRequestDTO.java`
- 参考：probe `InterviewGraphDemo.EvaluationResult` 的 `.entity()` 用法；ThinkVerse `InterviewReportGenerator`

- [ ] **Step 1: 定义 InterviewPlan 结构化输出**

```java
public record InterviewPlan(
        String overallStrategy,
        Map<String, AgentAssignment> agentAssignments,  // technical/project/coding
        List<String> weakPointPriority,
        int estimatedTotalRounds) {
    public record AgentAssignment(String topics, String difficulty, int estimatedRounds) {}
}
```

- [ ] **Step 2: PlanGenerator**——prompt 注入简历原文 + JD 原文 + 方向 + 时长，`chatClient.prompt().user(prompt).call().entity(InterviewPlan.class)`；失败降级为默认计划（3 方向 × 4 轮）。注意：**prompt 拼接用字符串，勿用 `.text()` 模板 + JSON 花括号**（probe 验证过的坑）
- [ ] **Step 3: `POST /api/interviews/plan`**：`{resumeId?, jdId?, direction?, persona, durationMinutes}` → `{interviewId, plan}`
- [ ] **Step 4: 单测**：mock ChatClient 返回固定 JSON，断言解析成功；LLM 抛异常时断言降级计划
- [ ] **Step 5: 提交** `feat(plan): 面试计划引擎`

### Task 1a-9: StateGraph 单 Agent 面试图（核心）

**Files:**
- Create: `interview/graph/InterviewState.java`、`interview/graph/InterviewGraphBuilder.java`、`interview/graph/node/PlanNode.java`、`interview/graph/node/AskNode.java`、`interview/graph/node/EvaluateNode.java`、`interview/agent/tool/AskQuestionTool.java`、`sse/SseRegistry.java`
- 参考：`ThinkVerse\probe\src\main\java\com\probe\demo1\InterviewGraphDemo.java`（StateGraph 构建骨架）、`ThinkVerse` 的 `AskQuestionTool` + `SseRegistry`（HL + SSE 移植）

- [ ] **Step 1: 移植 SseRegistry**（`ConcurrentHashMap<sessionId, FluxSink>` + register/unregister/send/sendComplete + 重连替换 + 旧连接异步清理）
- [ ] **Step 2: 移植 AskQuestionTool**（HL 模式：保存题目 → SSE 推送 → `CompletableFuture.get(30min)` 阻塞 → 返回候选人回答；`submitAnswer(sessionId, answer)` 唤醒）
- [ ] **Step 3: 构建图**（简化版，Phase 1b 再加多 Agent 节点）

```java
// 骨架（完整代码参考 probe/InterviewGraphDemo）
StateGraph graph = new StateGraph(keyStrategyFactory());   // 全部 ReplaceStrategy
graph.addNode("plan", node_async(planNode));               // 注入 interviewPlan
graph.addNode("ask", node_async(askNode));                 // AskQuestionTool 出题+等回答
graph.addNode("evaluate", node_async(evaluateNode));       // LLM 结构化评估 + 规则降级
graph.addEdge(START, "plan");
graph.addEdge("plan", "ask");
graph.addEdge("ask", "evaluate");
// 条件边：达标或轮次超限 → END；未达标 → 回到 ask（追问/换题）
graph.addConditionalEdges("evaluate",
        edge_async(state -> shouldEnd(state) ? "end" : "ask"),
        Map.of("ask", "ask", "end", END));
// MysqlSaver 持久化（参考 probe：SaverConfig.register(saver) + threadId=sessionId）
CompiledGraph compiled = graph.compile(CompileConfig.builder()
        .saverConfig(SaverConfig.builder().register(mysqlSaver).build())
        .recursionLimit(50).build());
```

- [ ] **Step 4: 单测**：mock LLM 评估返回固定分数，验证（a）低分循环回退次数、（b）超限强制结束、（c）`getStateHistory` 数量随轮次增长、（d）同 threadId 二次 invoke 从 checkpoint 续跑
- [ ] **Step 5: 提交** `feat(graph): StateGraph 单 Agent 面试图 + Checkpoint 持久化`

### Task 1a-10: 面试会话 API + SSE 端点

**Files:**
- Create: `interview/controller/InterviewController.java`、`interview/InterviewService.java`、`interview/InterviewSessionMapper.java`、`interview/InterviewRoundMapper.java`、`InterviewStartDTO.java`、`InterviewAnswerDTO.java`
- API：`POST /api/interviews/plan` → `POST /api/interviews/start`（SSE 长连接启动，返回 sessionId + 首题）→ `POST /api/interviews/{id}/answer`（提交答案 → SSE 流接收下一题/结束）→ `POST /api/interviews/{id}/end` → `GET /api/interviews/sessions` → `GET /api/interviews/sessions/{id}` → `GET /api/interviews/sessions/{id}/report`

- [ ] **Step 1: 会话创建**：状态机 `planned → in_progress → completed/interrupted`；每轮评估结果写 `interview_round`（evaluation JSON）
- [ ] **Step 2: SSE 事件协议**（移植 ThinkVerse）：`CONNECTED` / `QUESTION` / `FOLLOW_UP` / `THINKING` / `COMPLETE` / `REPORT_READY` / `ERROR`
- [ ] **Step 3: 集成测试**：启动面试 → 提交 5 轮答案 → 结束 → 断言消息落库、报告字段完整
- [ ] **Step 4: 提交** `feat(interview): 会话管理 + SSE 端点`

### Task 1a-11: 面试报告

**Files:**
- Create: `interview/report/InterviewReport.java`（record）、`interview/report/ReportGenerator.java`
- 参考：ThinkVerse `InterviewReportGenerator`

- [ ] **Step 1: InterviewReport record**：`overallScore, dimensionScores, strengths, weaknesses, suggestions, perQuestionFeedback[]`
- [ ] **Step 2: ReportGenerator**：面试结束后异步调用（`@Async` 线程池）——收集所有轮次 → `.entity(InterviewReport.class)` → 存 `interview_session.report` → SSE 推送 `REPORT_READY`
- [ ] **Step 3: 单测**：mock LLM 验证 JSON 解析与落库时序（先落库再推送）
- [ ] **Step 4: 提交** `feat(report): 面试报告生成`

### Task 1a-12: 前端面试流程页

**Files:**
- Create: `frontend/src/utils/sse.ts`（移植 ThinkVerse 的 fetch+ReadableStream POST SSE 解析器）、`views/InterviewStartView.vue`（选简历/JD/方向/时长/人格）、`views/InterviewRoomView.vue`（对话流 + SSE）、`views/ReportView.vue`、`components/ChatBubble.vue`

- [ ] **Step 1: 移植 SSE 解析器**（buffer 拼接 + `\n\n` 事件边界 + AbortController 取消）
- [ ] **Step 2: 面试房间**：题目卡片 + 答案输入框 + 发送 → SSE 流式渲染下一题/思考中/结束；刷新页面可通过 `GET /api/interviews/{id}/stream` 重连（复用 Session 的 SSE 长连接）
- [ ] **Step 3: 报告页**：渲染报告 JSON（总分 + 维度分 + 逐题反馈）
- [ ] **Step 4: 提交** `feat(frontend): 面试全流程页面`

### Task 1a-13: Phase 1a 验收

- [ ] **Step 1: 端到端验证清单**

```
1. 注册/登录/刷新 token 全流程
2. 上传 PDF 简历 → 列表可见 → 详情可读原文
3. 创建 JD（可选）
4. /api/interviews/plan 返回结构化计划（含 3 方向分配）
5. 发起面试 → 收到第一题（SSE QUESTION）→ 回答 → 收到下一题或追问
6. 连续 10-15 轮后自动结束（轮次上限）或手动结束
7. 每轮 interview_round 落库（evaluation JSON 含 score + knowledgePoints）
8. 报告生成：总分/维度分/逐题反馈完整
9. 面试中断（杀掉前端进程）→ 重启后同 sessionId 可从 Checkpoint 恢复（getStateHistory 验证）
```

- [ ] **Step 2: 成本检查**：单场 12 轮面试 token 消耗 ≈ ¥0.5 以内（qwen-plus）
- [ ] **Step 3: go/no-go**：全通过 → 进入 Phase 1b；StateGraph 异常（不应发生，已验证）→ 回退手写状态机

## 4. Phase 1b：多 Agent + 行为策略 + 长期记忆（3-4 周）

> 前置：Phase 1a 全验收通过。本阶段在 1a 的图上叠加差异化核心。

### Task 1b-1: 多 Agent 编排（Coordinator + 三 Agent + Speaker bypass）

**Files:**
- Create: `interview/agent/CoordinatorAgent.java`、`TechnicalAgent.java`、`ProjectAgent.java`、`CodingAgent.java`、`SpeakerAgent.java`、`graph/node/*AgentNode.java`
- Modify: `InterviewGraphBuilder`（多节点 + 条件边）

- [ ] **Step 1: Coordinator 节点**（qwen-turbo）：输入 = 计划 + 当前轮次 + 累计评估 + 已用时间 + 已问题目 → 结构化输出 `{nextAgent, reason, topic, difficulty}`；含安全终止（max_rounds=20、连续同 Agent 切换超 3 次强制切下一个未完成 Agent——文档 4.4.2）
- [ ] **Step 2: 三 Agent 出题节点**（qwen-plus）：各自角色 prompt + 计划方向 + 简历原文 + 已问题目摘要 + 薄弱点 → 出题；失败降级题库（预置 20 题/方向 JSON）
- [ ] **Step 3: Speaker bypass**（文档 4.4.1）：Phase 1 文字面试跳过 Speaker 节点直接输出（条件边判断 `phase == TEXT`），Phase 2 数字人再启用
- [ ] **Step 4: 条件边**：coordinator → 三 Agent 分发；evaluation → 行为策略分支
- [ ] **Step 5: 单测**：mock LLM 决策序列，验证 Agent 轮换、时间耗尽终止、反循环强制切换
- [ ] **Step 6: 提交** `feat(agent): 多 Agent 编排`

### Task 1b-2: 行为策略（BehaviorPolicy + 三预设）

**Files:**
- Create: `interview/policy/BehaviorPolicy.java`（接口：`shouldAllowRetry/shouldGiveHint/generateHint/evaluationStrictness/followUpStrategy`）、`PressurePolicy.java`、`GentlePolicy.java`、`NeutralPolicy.java`、`policy/BehaviorPolicyFactory.java`

- [ ] **Step 1: 实现三预设**（文档 3.8 表：压力型 false/false/STRICT；温和型 true/true/LENIENT；中性型 true(一次)/false/STANDARD）
- [ ] **Step 2: 接入 StateGraph 条件边**：评估后 → `score >= threshold` → coordinator；`< threshold` → `shouldAllowRetry()` 分支 → 提示/重试/切题
- [ ] **Step 3: 单测**：三预设 × {达标/未达标} 全组合断言分支走向
- [ ] **Step 4: 提交** `feat(policy): 人格行为策略`

### Task 1b-3: 出题去重 + 追问生成

**Files:**
- Create: `interview/agent/QuestionDeduper.java`、`interview/agent/FollowUpGenerator.java`

- [ ] **Step 1: 去重**：已问题目摘要注入出题 prompt（强制约束）+ 后端语义兜底（embedding 余弦 < 0.85 视为新题）
- [ ] **Step 2: 追问生成**：原始题 + 回答 + 评估（对错漏点）+ 人格 → 追问文本
- [ ] **Step 3: 单测**：同一主题连续 5 轮不重复（LLM 摘要断言）
- [ ] **Step 4: 提交** `feat(question): 出题去重与追问`

### Task 1b-4: Context 窗口管理

**Files:**
- Create: `interview/agent/ContextWindowManager.java`、`interview/agent/ConversationSummarizer.java`

- [ ] **Step 1: 滑窗**：对话历史 > 2000 token → 保留最近 3 轮完整 + 更早轮次 LLM 摘要（~200 token/轮）；窗口起点对齐 AssistantMessage（移植 ThinkVerse 滑动窗口安全截断）
- [ ] **Step 2: 预算监控**：prompt 构建时估算（中文 ≈ 1 token/字 粗估）> 6000 → 触发压缩；> 8000 → 硬性裁剪（仅系统 prompt + 计划 + 最近 1 轮）
- [ ] **Step 3: 集成验证**：20 轮面试，记录每轮实际 input token（DashScope 响应 usage），绘制增长曲线，断言峰值 < 6500
- [ ] **Step 4: 提交** `feat(context): Context 窗口管理`

### Task 1b-5: LLM 容错（重试 + 熔断 + 降级）

**Files:**
- Create: `common/ai/LlmCallWrapper.java`、`common/ai/LlmConfig.java`（Resilience4j：超时 60s / 重试 1 次退避 1s / 熔断）

- [ ] **Step 1: LlmCallWrapper<T>**：`callWithFallback(prompt, entityClass, fallbackSupplier)`（probe 已验证降级路径，正式封装）
- [ ] **Step 2: 全节点接入**：计划失败→规则计划；Coordinator 失败→按时间分配轮换；出题失败→题库；评估失败→规则评估（关键词+得分映射）；追问失败→跳过
- [ ] **Step 3: 单测**：模拟超时/500，断言降级输出可用
- [ ] **Step 4: 提交** `feat(resilience): LLM 调用容错`

### Task 1b-6: 长期记忆（knowledge_point + 三阶段归一化）

**Files:**
- Create: `memory/KnowledgePoint.java`、`KnowledgePointMapper.java`、`KnowledgePointService.java`、`KnowledgePointNormalizer.java`
- 参考：ThinkVerse `UserMemoryServiceImpl.findMatchingMemory`（0.85 阈值 embedding 归一化，已验证）

- [ ] **Step 1: 三阶段归一化**（文档 3.9）：① 评估 prompt 约束 topic 从计划枚举选择；② 精确匹配（去空格标点小写）→ embedding 余弦 > 0.85 归并 → 否则新建（verified=false）；③ 管理端未验证列表（Phase 3，先留字段）
- [ ] **Step 2: 写入时机**：每轮评估完成 → upsert（confidence 取最新、assessment_count+1、status 更新）
- [ ] **Step 3: 读取时机**：计划生成时注入 weak 知识点 TOP10 + 最近 3 场未考察盲区
- [ ] **Step 4: 单测**：「JVM GC 调优」vs「JVM 垃圾回收」归一化到同一行（mock embedding）；精确匹配不走 embedding
- [ ] **Step 5: 提交** `feat(memory): 长期记忆 + 知识点归一化`

### Task 1b-7: 报告增强 + 成长对比

**Files:**
- Modify: `interview/report/ReportGenerator.java`、`InterviewReport.java`
- Create: `interview/report/GrowthComparator.java`

- [ ] **Step 1: 报告增加**：`growthComparison{previousScore, currentScore, improvement}`（查历史最近一场分数）
- [ ] **Step 2: PDF 导出**：后端 OpenPDF（iText 兼容）生成 PDF → `GET /api/interviews/sessions/{id}/report.pdf`
- [ ] **Step 3: 前端**：报告页展示成长对比卡片 + 下载按钮
- [ ] **Step 4: 提交** `feat(report): 成长对比 + PDF 导出`

### Task 1b-8: Phase 1b 验收

- [ ] 多 Agent 按 StateGraph 编排，Coordinator 决定轮次交接（日志可见 agent 轮换与 reason）
- [ ] 出题 20 轮不重复；追问基于评估结果
- [ ] 三种人格产生不同流程分支（压力型不重试 / 温和型给提示）
- [ ] 面试结束知识点写入 knowledge_point；第二场面试计划注入 weak 知识点并优先考察
- [ ] Context 峰值 token < 6500（20 轮）
- [ ] LLM 模拟断网时降级生效，面试不中断
- [ ] 报告含成长对比；PDF 可下载
- [ ] 成本：单场 12 轮 ≤ ¥1.0

## 5. Phase 1c：Live Coding（2-3 周）

> 前置：Phase 1b 验收通过。本阶段实现"写代码 → 沙箱执行 → 测试用例 → 评估"闭环。

### Task 1c-1: Monaco Editor 集成

**Files:**
- Create: `frontend/components/CodeEditor.vue`、`views/CodingRoomView.vue`（或嵌入 InterviewRoom）
- 依赖：`monaco-editor`

- [ ] **Step 1: CodeEditor 组件**：语言切换（Java/Python）、语法高亮、运行/提交按钮、测试结果面板（进度条 + 用例明细）
- [ ] **Step 2: 提交** `feat(frontend): Monaco 编辑器`

### Task 1c-2: Docker 沙箱

**Files:**
- Create: `coding/sandbox/SandboxService.java`、`coding/sandbox/SandboxConfig.java`、`coding/sandbox/DockerSandboxExecutor.java`、`resources/docker/Dockerfile.java`、`resources/docker/Dockerfile.python`
- 依赖：`com.github.docker-java:docker-java-core:3.4.x` + `docker-java-transport-httpclient5`

- [ ] **Step 1: 镜像**：`eclipse-temurin:21-jre-alpine`（编译用 jdk 镜像 `eclipse-temurin:21-alpine`）+ `python:3.12-slim`（pytest）；先本地 build 推送私有 registry 或直接用 Dockerfile 挂载编译
- [ ] **Step 2: 执行器**：容器创建参数（文档 3.6 安全强化）——`--network none`、`--read-only`、`--cpus 1`、`--memory 512m`、`--pids-limit 64`、30s 超时 `docker kill`；代码挂载为卷（只读 fs 时挂 tmpfs 放源码）
- [ ] **Step 3: 代码安全检查**：静态黑名单（`Runtime.getRuntime().exec`、`ProcessBuilder`、`Class.forName`、`java.lang.reflect`、`Socket` 等编译期拒绝）
- [ ] **Step 4: 单测（关键）**：10 类恶意样本（网络/文件写/反射/fork bomb/内存耗尽）全部拦截或受限；30s 死循环被杀；512MB OOM kill
- [ ] **Step 5: 提交** `feat(sandbox): Docker 沙箱执行`

### Task 1c-3: 测试用例引擎

**Files:**
- Create: `coding/testcase/TestCaseService.java`、`coding/testcase/TestCaseGenerator.java`、`coding/controller/CodingController.java`
- API：`POST /api/coding/run`（运行：code + language + testCases → results/passRate/errors/executionTime）、`POST /api/coding/submit`（提交触发评估）

- [ ] **Step 1: 预设用例执行**：随题 JSON 下发（输入/期望输出/超时），沙箱内运行断言
- [ ] **Step 2: AI 动态用例**（qwen-plus）：题 + 候选代码 + 预设用例通过情况 → "找出未覆盖边界，生成额外用例"（结构化输出 List<TestCase>）；新增用例跑第二遍
- [ ] **Step 3: 单测**：正确代码 100% 通过；边界 bug 代码被动态用例捕获
- [ ] **Step 4: 提交** `feat(coding): 测试用例引擎`

### Task 1c-4: StateGraph 挂起/恢复集成

**Files:**
- Modify: `InterviewGraphBuilder`（Coding 分支）、`interview/agent/CodingAgent.java`、`coding/CodingSubmitController.java`
- 参考：probe `InterruptResumeDemo`（已验证：interruptBefore + resume() 两轮通过）

- [ ] **Step 1: 挂起**：CodingAgent 出题 → 前端展示 Monaco → 图在 `codingWait` 节点前挂起（`CompileConfig.interruptBefore("codingWait")`），Checkpoint 落库，状态置 `waiting_code`
- [ ] **Step 2: 恢复**：`POST /api/coding/submit` → 按 threadId 加载 checkpoint → `RunnableConfig.builder().threadId(sessionId).resume().build()` → invoke 继续 → 沙箱评估 → 行为策略分支
- [ ] **Step 3: 超时**：30 分钟未提交 → 定时任务自动提交当前代码进入评估；候选人主动放弃 → 状态 `interrupted`
- [ ] **Step 4: 集成测试**：模拟 15 分钟挂起后恢复，断言 State 完整（round/题目/历史非空）
- [ ] **Step 5: 提交** `feat(graph): Coding 挂起/恢复`

### Task 1c-5: 代码评估 + 行为策略联动

**Files:**
- Create: `coding/CodeEvaluationEngine.java`（`.entity()` 输出：correctness/codeQuality/edgeCaseHandling/timeComplexity/testPassRate/suggestions）、`coding/CodeEvaluationResult.java`
- Modify: `BehaviorPolicy` 分支接入 Coding 流程（文档 3.6 人格联动表：压力型 false 直接切题 / 温和型给提示重试 / 中性型一次修改机会）

- [ ] **Step 1: 评估引擎** + 规则降级（testPassRate × 权重兜底）
- [ ] **Step 2: 人格联动条件边**
- [ ] **Step 3: 单测**：三预设 × {代码错误} 分支断言
- [ ] **Step 4: 提交** `feat(coding): 代码评估与人格联动`

### Task 1c-6: 容器池预热 + Phase 1c 验收

- [ ] **Step 1: 容器池**：常驻 2-3 个就绪容器（Java 2 + Python 1），空闲回收；首包延迟 < 2s
- [ ] **Step 2: 验收清单**：
```
1. Coding Agent 出题 → Monaco 编辑 → 运行（测试用例通过率实时返回）
2. 提交代码 → 图恢复 → 代码评估多维评分
3. 压力型代码写错直接切题；温和型给提示允许修改
4. 恶意代码全部被沙箱拦截（10 类样本）
5. 30 分钟未提交自动评估
6. 挂起 15 分钟恢复后 State 完整
7. 单场面试（含 2 道 Coding）成本 ≤ ¥1.5
```
- [ ] **Step 3: go/no-go** → 通过则 Phase 1 完成

## 6. ThinkVerse → ai-interview-agent 可复用资产清单

| # | 资产 | ThinkVerse 位置 | 移植方式 | 阶段 |
|:--|:---|:---|:---|:---|
| 1 | JWT 双 Token（JwtUtil/拦截器/BaseContext/刷新续期） | `thinkverse-common/utils/JwtUtil.java`、`server/interceptor/JwtTokenInterceptor.java` | 复制改包名 + jjwt 0.12.6 依赖 | 1a |
| 2 | 统一响应 Result<T>/PageResult<T> + 异常体系 | `thinkverse-common/result/`、`exception/` | 复制改包名 | 1a |
| 3 | Human-in-the-Loop（CompletableFuture 阻塞提问） | `server/service/ai/interview/AskQuestionTool`（PendingQuestionManager 模式） | 移植到 interview/agent/tool/AskQuestionTool | 1a |
| 4 | SSE 注册表（重连替换 + 旧连接异步清理） | `server/ai/sse/SseRegistry` | 移植到 sse/SseRegistry | 1a |
| 5 | 前端 POST SSE 解析器（fetch + ReadableStream） | `frontend/composables/useSse.ts` / useInterviewSse | 移植到 frontend/src/utils/sse.ts | 1a |
| 6 | 记忆归一化（精确匹配 + embedding 0.85 阈值 + 失败降级） | `server/.../UserMemoryServiceImpl.findMatchingMemory` | 移植到 KnowledgePointNormalizer | 1b |
| 7 | 滑动窗口安全截断（对齐 AssistantMessage） | ThinkVerse 面试滑动窗口实现 | 移植到 ContextWindowManager | 1b |
| 8 | LLM 降级兜底模式 | ThinkVerse 各 AI 服务 catch → 规则/模板 | 模式复用 → LlmCallWrapper | 1a/1b |
| 9 | StateGraph 骨架（MysqlSaver + 条件边 + interrupt/resume） | `ThinkVerse/probe/`（已验证 6 项能力） | 直接作为 InterviewGraphBuilder 基础 | 1a |
| 10 | 简历 PDF 解析经验（PDFBox → Tika 的迁移要点） | `server/.../ResumeParser` | 仅参考；正式用 Tika | 1a |
| 11 | 登录/注册页 UI + axios 拦截器（401 刷新） | `frontend/pages/login.vue`、`utils/request.ts` | 参考重构（新 UI 风格） | 1a |

**明确不复用**：知识库/RAG/文章/ES/内容总结（差异化决策：新项目聚焦面试，不引入知识库载体）；Langfuse 可观测性（暂缓）。

## 7. 风险更新（相对 technical-design.md 第九章）

| 风险 | 原评级 | 现状 | 说明 |
|:---|:---|:---|:---|
| R1 StateGraph 成熟度 | 高 | ✅ 已解除 | 6 项能力验证通过（probe 项目） |
| R2 异步 Coding 挂起 | 高 | ✅ 已解除 | interruptBefore + resume 两轮验证通过 |
| R3 Context 窗口溢出 | 高 | 1b 验证 | 沿用 ThinkVerse 滑窗策略 + 预算监控 |
| R4 知识点归一化 | 高 | 1b 验证 | 移植 0.85 阈值实现（ThinkVerse 已跑通） |
| R5 LLM 评估稳定性 | 中 | 1a 验证 | 结构化输出已实测可用；方差评估列入 1a 验收 |
| R8 沙箱安全 | 中 | 1c 重点 | 多层隔离 + 10 类恶意样本单测 |
| R11 成本 | 中 | 1a 检查 | qwen-turbo 路由 + Speaker bypass，12 轮 ≤¥1 目标 |
| 新风险：重复建设 | — | 已缓解 | 第 6 节清单覆盖主要资产 |

## 8. 假设与待确认

- MySQL 容器端口 13307 / Redis 6380 无冲突（ThinkVerse 用 13306/6379）
- DashScope Key 继续用现有 sk- 标准 Key（qwen-plus/qwen-turbo 可用，已由 ThinkVerse 验证）
- 前端 UI 库用 Naive UI（与 ThinkVerse 一致）；若希望差异化外观可换 Element Plus，不影响本计划
- 简历上传存 MinIO 还是本地磁盘：**1a 先用本地磁盘**（`/data/resumes`），Phase 2 再评估对象存储（MinIO 经验可直接复用）
- 沙箱容器需 Docker Engine 挂载（宿主 docker.sock 或独立沙箱宿主），开发机即 Docker Desktop
