# 04 数据模型与 API 清单

> 知识库入口：[00-知识库索引.md](00-知识库索引.md) ｜ 上一篇：[03-功能模块实现详解.md](03-功能模块实现详解.md) ｜ 下一篇：[05-前端架构.md](05-前端架构.md)

---

## 1. 数据模型（数据库）

Flyway 迁移脚本位于 `src/main/resources/db/migration/`：

### 1.1 V1 基础表

| 表 | 关键字段 | 说明 |
|:---|:---|:---|
| `app_user` | id, username(UNIQUE), email, password_hash, role, status | 用户 |
| `resume` | id, user_id, file_name, file_type, file_size, raw_text, content_hash | 简历（全文文本） |
| `job_description` | id, user_id, title, raw_text, source_url | 职位描述 |
| `interview_session` | id(VARCHAR36 PK), user_id, resume_id, jd_id, direction, persona, duration_minutes, status, interview_plan(JSON), overall_score, report(JSON), started_at, completed_at | 面试会话 |
| `interview_round` | id, session_id, round_number, agent_name, topic, question, candidate_answer, evaluation(JSON), is_followup, followup_target | 轮次（追问轮 followup_target 指向主轮） |

### 1.2 V2 长期记忆

| 表 | 关键字段 | 说明 |
|:---|:---|:---|
| `knowledge_point` | id, user_id, topic, status(mastered/weak/untested), confidence, last_assessed, assessment_count, verified, **UNIQUE(user_id, topic)** | 知识点记忆 |

### 1.3 V3/V4 编程题

| 表 | 关键字段 | 说明 |
|:---|:---|:---|
| `coding_submission` | id, session_id, round_number(默认0), code, language, test_results(JSON), pass_rate, evaluation(JSON), status(pending/...) | 代码提交记录 |

### 1.4 V5 断线恢复

- `interview_session` 增加 `current_question` 字段（当前待回答题目，回答后清空）。

### 1.5 V6 知识库 RAG

| 表 | 关键字段 | 说明 |
|:---|:---|:---|
| `knowledge_base` | id, user_id, name, description, created_at | 面试官私有知识库 |
| `knowledge_document` | id, kb_id, title, content_md, status(DRAFT/VECTORIZING/ACTIVE/FAILED), chunk_count | 知识库文档（向量化状态机） |

- `interview_session` 增加 `knowledge_base_id` 字段（可空，挂载的知识库）。
- 向量数据不在 MySQL：存 Elasticsearch 索引 `spring-ai-document-index`（metadata：kbId/docId/title/chunkIndex）。

### 1.6 V7/V8 LLM 观测（llm_trace）

| 表 | 关键字段 | 说明 |
|:---|:---|:---|
| `llm_trace` | id, session_id(可空), trace_id(轮次关联ID), agent, kind(llm/retrieval), eval_score(评分回写), model, prompt_tokens, completion_tokens, total_tokens, duration_ms, status(success/error), error_msg, estimated_cost(DECIMAL(10,6)), prompt_excerpt(TEXT), completion_excerpt(TEXT), created_at | 每次 LLM 调用/知识库检索一行；索引 session_id、trace_id 与 created_at |

- V8 迁移新增三列：`trace_id`（CoordinatorNode 派发新题时生成 `rt-*`，同轮出题/检索/评分/追问共享）、`kind`（默认 `llm`，检索 span 为 `retrieval`，token/成本恒 0）、`eval_score`（EvaluateNode 按 trace_id 异步回写本轮调整分）。
- 汇总统计只统计 `kind='llm'` 行，检索 span 不污染 token/成本口径。
- 评测运行的会话 sessionId 形如 `eval-{caseId}-{ts}`，可按此前缀在 llm_trace 中聚合评测的 token/成本消耗。

### 1.7 自动创建表（MysqlSaver）

- `GRAPH_CHECKPOINT` / `GRAPH_THREAD`：StateGraph checkpoint 持久化表（`CreateOption.CREATE_IF_NOT_EXISTS` 自动建）。

---

## 2. API 清单

### 2.1 认证（无需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/auth/register` | 注册（username/password/email）→ 双 Token |
| POST | `/auth/login` | 登录 → 双 Token |
| POST | `/auth/refresh` | 刷新 Token |

### 2.2 简历 / JD（需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/resumes/upload` | multipart 上传（PDF/DOCX/DOC/TXT ≤10MB） |
| GET | `/api/resumes` | 列表 |
| GET / DELETE | `/api/resumes/{id}` | 详情 / 删除 |
| POST | `/api/jds` | 创建 JD（title + raw_text） |
| GET | `/api/jds` | 列表 |
| GET / DELETE | `/api/jds/{id}` | 详情 / 删除 |

### 2.3 面试（需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/interviews/plan` | 生成面试计划 |
| POST | `/api/interviews/start` | **SSE** 启动面试（body：resumeId/jdId/direction/persona/durationMinutes/knowledgeBaseId 可选） |
| GET | `/api/interviews/{id}/stream` | **SSE** 重连流 |
| POST | `/api/interviews/{id}/answer` | 提交答案（唤醒图阻塞） |
| POST | `/api/interviews/{id}/end` | 手动结束（interrupted + 生成报告） |
| GET | `/api/interviews/sessions` | 会话列表 |
| GET | `/api/interviews/sessions/{id}` | 会话详情（含 currentQuestion 用于断线恢复） |
| GET | `/api/interviews/sessions/{id}/rounds` | 轮次列表 |
| GET | `/api/interviews/sessions/{id}/report` | 报告 JSON |
| GET | `/api/interviews/sessions/{id}/report.pdf` | 报告导出（当前为 TXT） |

### 2.4 编程题（需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/coding/run` | 练习：运行代码（带用例则跑测试，无预设用例直接执行并返回沙箱 stdout 供控制台展示） |
| POST | `/api/coding/submit` | 练习：用例 + 动态用例 + 多维评估 |
| POST | `/api/coding/submit/{sessionId}` | **面试**：提交代码恢复图执行 |

### 2.5 知识库（需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/api/knowledge-bases` | 创建知识库（name/description） |
| GET | `/api/knowledge-bases` | 列表（当前用户） |
| GET / DELETE | `/api/knowledge-bases/{id}` | 详情 / 删除（先删 ES 向量再删记录） |
| POST | `/api/knowledge-bases/{kbId}/documents` | 添加文档（title/contentMd/vectorize） |
| GET | `/api/knowledge-bases/{kbId}/documents` | 文档列表（含 status/chunkCount） |
| GET | `/api/knowledge-bases/{kbId}/documents/{docId}` | 文档详情 |
| PUT | `/api/knowledge-bases/{kbId}/documents/{docId}` | 更新文档（可触发重新向量化） |
| DELETE | `/api/knowledge-bases/{kbId}/documents/{docId}` | 删除文档（同步清理向量） |

### 2.6 评测（需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| GET | `/api/eval/cases` | 列出 golden 用例摘要 |
| POST | `/api/eval/run` | 提交评测运行（异步）：body 可选 `caseIds`（空=全量）/`skipLlmJudge`（只跑规则指标）/`runCalibration`（随行校准）→ 返回 runId |
| GET | `/api/eval/runs/{runId}` | 查询运行状态（RUNNING/DONE/FAILED）与报告（报告同时落盘 `./eval-reports/`） |
| POST | `/api/eval/calibrate` | 同步执行 judge 校准（人工标注一致率） |

### 2.7 LLM 观测（需 Token）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| GET | `/api/observability/sessions?limit=` | 会话维度汇总（调用数/总 token/成本/错误数，默认最近 50 场） |
| GET | `/api/observability/traces?sessionId=` | 单场面试 LLM 调用与检索 span 明细（时间升序，含 traceId/kind/evalScore） |
| GET | `/api/observability/summary?days=` | 汇总统计（总 token/成本/错误数 + 按 agent 拆分，默认近 7 天） |

---

*文档结束。如有与代码不一致之处，以代码为准。*
