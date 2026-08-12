# ai-interview-agent

面向技术面试场景的智能多 Agent 面试平台：候选人上传简历（可选 JD）后，系统自动生成面试计划，由多个"面试官 Agent"（技术基础 / 项目经验 / 编码能力）基于 **Spring AI Alibaba StateGraph** 协同完成一场结构化、可断点恢复的模拟面试，最终输出带多维评分的面试报告与成长对比。

同时内置 **Agent 评测体系**（golden 用例驱动 + 规则指标 + LLM-as-Judge + 人工标注校准）与 **LLM 可观测性**（每次调用的 token / 耗时 / 成本归因落库，前端观测台可视化）。

---

## 核心特性

| 特性 | 说明 |
|:---|:---|
| 多 Agent 编排 | StateGraph 状态化工作流（plan → coordinator → ask → evaluate → followUp），条件边路由、Checkpoint 持久化、挂起/恢复 |
| 编程题全流程 | Monaco 编辑器 → Docker 沙箱硬隔离执行 → AI 动态测试用例 → 多维代码评估 → 不达标按人格重试 |
| 人格行为策略 | 压力型 / 温和型 / 中性型面试官，影响评分严格度、重试、提示、追问行为 |
| 长期记忆 | 每轮评估知识点落库，下一场面试计划自动注入薄弱点优先考察 |
| 知识库 RAG | 私有知识库管理 + 异步向量化（ES 8 + text-embedding-v3），出题与评分环节自动检索注入 |
| 评测体系 | golden 用例进程内驱动完整面试，规则指标回归门禁 + LLM-as-Judge + 评分器校准，前端 `/eval` 页可视化 |
| LLM 可观测性 | Spring AI Observation 自动埋点，每次调用落 `llm_trace`（token/成本/Agent 归因），前端观测台 |
| LLM 容错 | 统一超时/重试/降级兜底，LLM 不可用时面试不中断（降级题库、规则评分、诚实标记 degraded） |
| 实时交互 | SSE 推送题目/追问/报告事件，前端轮询兜底双保险防卡死 |

## 技术栈

- **后端**：Java 21 + Spring Boot 3.5.8 + Spring AI Alibaba 1.1.2.0（DashScope Starter + StateGraph）+ MyBatis + Flyway + JWT 双 Token
- **大模型**：qwen3.7-max（出题/评分/计划，**必须 `enable-thinking: true`**）、qwen-turbo（Coordinator 路由）、text-embedding-v3（向量化）
- **基础设施**：MySQL 8.0、Elasticsearch 8.13.4（知识库向量）、Redis、Docker（沙箱）
- **前端**：Vue 3 + Vite + TypeScript + Naive UI + Monaco Editor + Pinia

## 快速开始

### 环境要求

JDK 21+、Maven、Node.js 18+、Docker Desktop。

### 1. 基础设施

```bash
docker compose up -d                     # MySQL(13307) + Redis(6380)

# Elasticsearch（知识库功能依赖；不启动时知识库不可用，面试主链路不受影响）
docker run -d --name ai-interview-es -p 9200:9200 \
  -e discovery.type=single-node -e xpack.security.enabled=false \
  docker.elastic.co/elasticsearch/elasticsearch:8.13.4
```

### 2. 配置 API Key

复制 `src/main/resources/application-local.yml.example` 为 `application-local.yml`，填入 DashScope api-key（不入库；也可用环境变量 `DASHSCOPE_API_KEY` 覆盖）。

使用自定义 MaaS 端点时，用启动参数覆盖：

```
--spring.ai.dashscope.base-url=https://your-maas-endpoint
--spring.ai.dashscope.chat.options.model=<your-model>
--spring.ai.dashscope.chat.options.enable-thinking=true
```

其他环境变量：`JWT_SECRET`（生产必改）、`DB_PASSWORD`（默认 root123）。

### 3. 启动

```bash
# 后端（首次自动 Flyway 建表 + 沙箱镜像预热）
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev                               # http://localhost:5173
```

## 功能使用

### 模拟面试

注册登录 → 上传简历（PDF/DOCX/TXT）→ 可选创建 JD / 知识库 → 「开始面试」选择方向、人格、时长、知识库 → SSE 实时对话；编程题跳转 Monaco 编辑器作答，不达标按人格给提示重试；结束生成报告（总分/维度分/逐题点评/成长对比）。

### Agent 评测（/eval）

用 golden 数据集自动驱动完整面试链路，评估 Agent 编排 / 出题 / 评分质量：

- 前端 `/eval` 页勾选用例发起，或 REST：
  - `POST /api/eval/run`：body 可选 `caseIds` / `skipLlmJudge`（只跑规则指标）/ `runCalibration`
  - `GET /api/eval/runs/{runId}`：查询进度与报告
  - `GET /api/eval/cases`、`POST /api/eval/calibrate`
- 指标三层：**规则指标**（完成率 / 目标达成 / 轮次达成 / 主题覆盖 / 重复率 / 跑题数 / 降级率，确定性回归门禁）+ **LLM-as-Judge**（出题相关性 / 追问针对性）+ **人工标注校准**（评分器与预期档位一致率）
- 报告 JSON 落盘 `eval-reports/{runId}.json`，供版本间回归对比

### LLM 观测台（/observability）

调用数 / Token / 估算成本概览，按 Agent 归因拆分，单会话调用链明细（状态 / 耗时 / Prompt 摘录）。

## 项目结构

```
src/main/java/com/interview/agent/
├── auth/            # JWT 双 Token 认证
├── resume/  jd/     # 简历（Tika 解析）与 JD
├── interview/       # ★ 核心：StateGraph 图编排、Agent、计划、人格策略、报告
├── coding/          # ★ 编程题：沙箱执行、AI 测试用例、多维评估
├── memory/          # 长期记忆（知识点归一化 upsert）
├── knowledge/       # 知识库 RAG（异步向量化 + 检索注入）
├── eval/            # ★ 评测体系（golden 用例驱动 + 规则/Judge/校准指标）
├── observability/   # LLM 调用观测与成本统计
├── sse/             # SSE 连接注册表
└── common/          # LlmCallWrapper 容错、拦截器、统一响应

frontend/src/        # Vue3 前端：面试间/编程间/报告/知识库/观测台/评测页
```

## 文档

完整代码知识库见 [`docs/reference/`](docs/reference/00-知识库索引.md)：

- [01 项目概览与目录结构](docs/reference/01-项目概览与目录结构.md)
- [02 系统架构与 Agent 架构详解](docs/reference/02-系统架构与Agent架构详解.md)（理解核心架构首选）
- [03 功能模块实现详解](docs/reference/03-功能模块实现详解.md)
- [04 数据模型与 API 清单](docs/reference/04-数据模型与API清单.md)
- [05 前端架构](docs/reference/05-前端架构.md)
- [06 设计决策、运行指南与未来方向](docs/reference/06-设计决策运行指南与未来方向.md)（修改核心逻辑前必读）

## 注意事项

- **qwen3.7-max 必须开启 thinking**：不配 `enable-thinking: true` 所有 LLM 调用报 HTTP 400。
- 评测驱动真实面试链路（含 LLM 与沙箱），单用例约 5-15 分钟；评测会话 sessionId 前缀 `eval-`，依赖已注册的评测账号（默认 `testuser`）。
- 沙箱依赖 Docker：Java 用 `eclipse-temurin:21-jdk-alpine`、Python 用 `python:3.12-slim`（启动时自动预热）。
