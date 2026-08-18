# 知识笔记 AI 问答（RAG Chat）设计

日期：2026-08-13 | 分支：feature/knowledge-chat | 状态：已确认

## 背景与决策

- 知识库当前仅服务面试（出题参考 + 评分依据），用户认为价值低。
- 决策：**彻底解耦**——面试不再关联知识库，出题/评分回归纯 LLM；知识库独立为"知识笔记"模块，新增基于当前用户**全部文档**的 AI 问答（多轮对话 + SSE 流式 + 引用来源），对话历史完整落库可回看。

## 核心机制

### 严格拒答（Grounded）

1. 提问 → 跨库检索当前用户全部 ACTIVE 文档（top-5，相似度 ≥0.5）
2. 命中为空 → 返回固定拒答文案，**不调 LLM**："该问题超出了你当前知识库范围，我无法回答。可以在知识笔记中补充相关文档后再试。"
3. 命中非空 → Prompt 硬约束："只能依据提供的知识片段回答；片段中没有的信息必须明确说'知识库中没有相关内容'，禁止用自有知识补全或推测"
4. 答案附带来源（命中文档标题 + 片段，前端可展开）

### 用户隔离（信任边界：userId 只从登录态取，前端传入的 ID 仅作查找键）

| 攻击面 | 防御 |
|---|---|
| 检索跨用户 | kbId 集合由服务端按 userId 查 DB 得出，ES 用 `kbId in [...]` 硬过滤 |
| 越权读会话 | 所有 chat 接口校验 `session.userId == 当前用户`，失败返回 404 |
| 上下文伪造 | 多轮历史只从 DB 读（读前校验归属），绝不接收前端传入的历史消息 |
| 向量元数据伪造 | 向量写入仅后端执行，metadata kbId 取自 DB 记录 |
| SSE 串话 | 每次 ask 独立 SseEmitter，短生命周期，不进面试 SseRegistry |

## 数据模型（V9__chat.sql）

```sql
chat_session (id PK AI, user_id BIGINT NOT NULL, title VARCHAR(200), created_at, updated_at, INDEX(user_id))
chat_message (id PK AI, session_id BIGINT NOT NULL, role VARCHAR(20) /*user|assistant*/,
              content MEDIUMTEXT, sources JSON /*命中文档：[{docId,title,excerpt}]*/, created_at, INDEX(session_id))
```

## 后端（chat 包）

- `ChatController`：
  - POST `/api/chat/sessions` 新建（标题默认取首问前 20 字，创建时可为空）
  - GET `/api/chat/sessions` 当前用户会话列表（按 updated_at 倒序）
  - GET `/api/chat/sessions/{id}/messages` 历史（归属校验）
  - DELETE `/api/chat/sessions/{id}` 删除（归属校验，连带消息）
  - POST `/api/chat/sessions/{id}/ask`（body: `{question}`）→ SseEmitter 流式
- `ChatService`：归属校验 → 落 user 消息 → 服务端查 kbIds → 检索 → 拒答或流式生成 → 完成后落 assistant 消息（含 sources）→ 更新会话 title/updated_at
- SSE 事件：`delta`（增量文本）、`sources`（来源数组）、`refusal`（拒答文案）、`done`、`error`
- `KnowledgeRetriever` 重构：原 `search(kbId,...)` 删除（面试已解耦），新增 `searchByKbIds(List<Long> kbIds, String query, int topK)` 返回结构化 `RetrievedChunk(docId,title,excerpt,score)`；retrieval span 埋点保留
- LLM：`chatClient.prompt().user(...).stream().content()` Flux 逐块推送；观测落 llm_trace（agent=chat）
- 异常：LLM 失败发 `error` 事件并落库 assistant 消息标注失败；检索异常视作无命中走拒答（保守）

## 面试解耦清理（回归纯 LLM）

- `InterviewStartDTO`：删 `knowledgeBaseId` 字段
- `InterviewService`：删归属校验、session/state 赋值三处
- `InterviewStartView.vue`：删知识库选择器、kbOptions、query 传参；`InterviewRoomView.vue`：删 query 接收与透传
- `CoordinatorNode`/`EvaluateNode`：删检索注入；`TechnicalAgent`/`ProjectAgent.generateQuestion` 删 `referenceKnowledge` 参数；`AnswerEvaluator` 删三参重载
- `InterviewSession.knowledgeBaseId` 实体字段与 DB 列**保留**（历史数据兼容），仅不再写入，注释标记废弃

## 前端

- `api/chat.ts` + `ChatView.vue`（路由 `/chat`）：左侧会话列表（新建/删除/切换），右侧消息流（流式打字、来源折叠条、拒答灰色提示样式、Enter 发送）
- HomeView 增加"知识问答"入口卡片
- 面试回归：发起页无选库 UI，出题正常

## 测试

1. 后端：编译、启动、curl 全接口（有知识命中 / 无知识拒答 / 越权 404 / 历史 / 删除）
2. 隔离：A 账号访问 B 账号会话与提问，验证不泄漏
3. 前端：浏览器全流程（新建会话→提问→流式→来源→拒答→历史回看→继续对话）
4. 面试回归：无库发起面试，出题/评分正常（纯 LLM）
