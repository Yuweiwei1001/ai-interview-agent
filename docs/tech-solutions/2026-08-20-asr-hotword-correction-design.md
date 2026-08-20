# 语音链路 ASR 术语纠错与热词偏置 — 技术方案

日期：2026-08-20
状态：待评审（v3，已按评审意见修订并脱敏）

> **修订记录**
> - v3（2026-08-20）：文档脱敏整理，移除外部引用与内部信息；本项目（个人开源项目）代码与公开 API（DashScope/qwen3-asr-flash-realtime）保留。
> - v2（2026-08-20）：按评审意见修订——① seq 对齐机制（subtitle/correction 统一序号，P1-1）；② 幻觉检测判据由字符重叠率改为编辑距离、处置由丢弃改为 suspect 标记降级（P1-2）；③ 热词快照持久化载体明确为 graph state 的 InterviewState 字段，消除断线恢复静默失效坑（P1-3）；④ 新增纠错与手动编辑的竞态规则（P1-4）；⑤ 检索零候选短路 LLM 调用（P2-5）；⑥ 干扰集门禁固定热词快照（P2-6）；⑦ 种子词表人工抽查优先英文发音歧义词（P3）。
> - v1（2026-08-20）：初稿。

## 1. 背景与问题

### 1.1 当前语音链路

语音面试使用 DashScope `qwen3-asr-flash-realtime`（Omni Realtime WebSocket，见 `VoiceAsrService`）：

```
麦克风 PCM ──WS──► ASR 引擎（server_vad 切段）
                      ├─ partial（.text/.delta）─► 前端实时字幕预览
                      └─ final（.completed）─────► 前端累积为回答草稿
                                                     └─ 候选人手动提交 ─► 评估 Agent
```

ASR final 定稿文本**未经任何质量处理**，直接进入产品链路。

### 1.2 问题：ASR 同音/近音错误的传播路径

技术面试是 ASR 错误的重灾区——候选人满口 "Kubernetes"、"MVCC"、"零拷贝"、"Raft"，ASR 常输出"库伯内提斯"、"拉夫特"等同音错字。错误沿三条路径传播，危害递增：

| # | 传播路径 | 危害 |
|---|---|---|
| 1 | 草稿质量 | 错词堆积，候选人手动修改负担重（当前"手动提交"实为人肉纠错兜底） |
| 2 | 评分失真 | 评估 Agent 拿到错误术语，可能把"答对了"评成"答错了" |
| 3 | **长期记忆污染** | 每轮评估知识点落库（`KnowledgePointService`），下一场面试计划自动注入薄弱点——ASR 错误产生的错误知识点**跨场次传播**，且不可自动恢复 |

### 1.3 设计取舍：重量级方案 vs 本方案

ASR 术语纠错领域存在一类重量级解法：**音素级热词检索 + LLM 保守纠错**，其两个关键价值已被广泛验证：纠错效果显著优于传统 NER；可通过干扰测试集（无错误文本）验证 WER 零上升，即纠错不引入幻觉。本方案在各维度按量级裁剪：

| 维度 | 重量级做法 | 本项目取舍 |
|---|---|---|
| 热词检索 | 音素级检索（大规模人名词库、毫秒级延迟要求） | **拼音归一化 + 编辑距离**（中文同音错误为主，量级小，内存暴力即可） |
| LLM 纠错 | 大参数模型，分批纠错 | qwen-turbo，以 VAD 句子为天然 chunk |
| 纠错模式 | 全自动无人干预（要求极端保守） | **建议式**（候选人手动提交环节天然存在人工确认，可更积极） |
| 开关灰度 | 新旧双链路 + 多开关灰度矩阵（海量流量） | 单体服务两个 feature flag 足够 |
| 失败兜底 | 纠错失败返回原文，不降级旧链路 | 同样：纠错失败回退原文，复用 `LlmCallWrapper` 模式 |
| 口语书面化 | 能力之一 | **不适用**：面试评估要看口语真实表达，书面化等于替候选人美化表达 |
| 源头热词偏置 | 无（纯后处理） | **新增**：ASR corpus 偏置（见 4.2），常规后处理方案未覆盖的能力 |

## 2. 总体方案

### 2.1 分层架构

```
┌─────────────────── 会话热词表（本场面试，几十个词，一份数据三处消费）───────────────────┐
│  来源①面试计划 hotwords（PlanGenerator 顺带输出，零额外调用）                            │
│  来源②简历 terms（上传时异步 LLM 抽取）      来源③ JD terms（上传时异步 LLM 抽取）      │
│  归一化去重（alias → canonical）后落库，开始面试时合并进 session 上下文                  │
└──────────┬──────────────────────────┬──────────────────────────────┬─────────────────┘
           │ (a) corpus 偏置           │ (b) 后处理纠错 Prompt          │ (c) 出题/评分 Prompt
           ▼                          ▼                              ▼
  ASR 引擎（源头减错）       后处理纠错（兜住漏网错误）          AskNode / EvaluateNode
  setCorpusText()           全局术语库拼音检索 top-K             评分容忍 ASR 噪声
           │                          │
           ▼                          ▼
    final 定稿文本 ───► 建议式纠错（高亮/点选）───► 候选人确认提交 ──► 评估链路
                                                      │
                评测门禁（噪声注入回归 + 干扰测试集零改动门禁）◄──────┘
```

三层防线：

1. **源头偏置**（ASR corpus）：会话级热词喂给 ASR，从识别阶段减少错误；
2. **后处理纠错**（全局术语库 + LLM）：兜住 corpus 没纠住的术语错误，建议式交互由候选人终审；
3. **评测门禁**（eval 体系扩展）：噪声注入回归指标 + 干扰集零改动门禁，保证增强链路"不能比没有更差"。

### 2.2 已确认的关键决策

| 决策点 | 结论 | 依据 |
|---|---|---|
| corpus 放什么 | **只放会话级热词（~几十个）**，不放全局术语库 | SDK 源码已验证 `OmniRealtimeTranscriptionParam.corpusText` 可用；但 corpus 存在幻觉输出风险（GitHub aliyun/alibabacloud-bailian-speech-demo#50：有概率整体输出 corpus 内容），词表越长破坏越大；且长 corpus 偏置稀释、90% 全局术语本场用不到 |
| 全局术语库放哪 | 后处理链路（自有拼音检索），**不进 ASR corpus、不进知识库向量索引** | 语义向量对同音错字检索失效（"拉夫特"与 Raft 语义距离极远）；知识库 RAG 是文档片段级/语义检索/给出题用，与词级/拼音检索/纠错用粒度不同，混用互相拖累召回 |
| 热词标签化时机 | 计划生成顺带输出 hotwords + 简历/JD 上传时异步抽取 | 复用知识库异步向量化既有模式；上传与面试间天然有时间差 |
| 纠错交互 | 建议式：高置信度直接替换+前端高亮，低置信度候选点选 | 候选人手动提交环节 = 天然人工确认，兜住幻觉，策略可比全自动纠错模式更积极 |
| 纠错时机 | 仅 final 定稿后异步执行，**partial 永不过 LLM** | 实时字幕延迟敏感；秒级纠错延迟预算仅适用于离线链路，不适用实时链路 |
| 失败兜底 | 纠错超时/异常回退原文，一句话都不多 | 复用 `LlmCallWrapper` 容错模式（失败回退原文的通用容错策略） |

### 2.3 非目标（YAGNI）

- 不做 IPA 音素比较器（适用于大规模词库 + 毫秒级硬约束场景，本项目量级用拼音即可）
- 不做新旧双链路灰度矩阵（单体服务两个开关闭环）
- 不做口语书面化（面试评估需要保留口语真实状态）
- 不做 corpus 控制台托管词表（paraformer vocabulary_id 模式，与本项目模型无关）
- 不做多语种（首版仅中文同音 + 英文术语拼写错误两类）

## 3. 数据模型

### 3.1 新表 `session_hotword`（V1x__session_hotword.sql）

```sql
CREATE TABLE session_hotword (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL COMMENT 'app_user.id',
    source_type  VARCHAR(10)  NOT NULL COMMENT 'resume / jd / plan',
    source_id    BIGINT       NOT NULL COMMENT 'resume.id / jd.id / interview_plan.id',
    term         VARCHAR(128) NOT NULL COMMENT '归一化后的规范写法，如 Redis、Spring Boot',
    category     VARCHAR(32)  DEFAULT NULL COMMENT 'framework / middleware / database / algorithm / protocol / language / system / other',
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source_term (source_type, source_id, term),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话级热词：简历/JD/面试计划抽取的技术术语';
```

同一来源重复上传时按 `(source_type, source_id)` 全删全插（简单幂等，不维护增量 diff）。

### 3.2 新表 `term_dict`（V1x__term_dict.sql，全局计算机术语库）

```sql
CREATE TABLE term_dict (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    term        VARCHAR(128) NOT NULL COMMENT '规范术语，如 Raft、零拷贝、MVCC',
    pinyin      VARCHAR(256) NOT NULL COMMENT '空格分隔读音序列，如 la fu te / ling kao bei（多音字用 | 分隔候选）',
    category    VARCHAR(32)  DEFAULT NULL,
    aliases     VARCHAR(512) DEFAULT NULL COMMENT 'JSON 数组：别名/常见错误写法，如 ["springboot","spring boot"]',
    enabled     TINYINT(1)   DEFAULT 1,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_term (term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='全局计算机术语词典（拼音检索用，不向量化）';
```

- `pinyin` 离线生成：中文/混合词用 TinyPinyin；纯英文按读音规则生成（Raft→la fu te），首版由 LLM 批量生成 + 人工抽查校准（**抽查优先覆盖英文发音歧义词**——Django（jango 罕见音节）、Kafka（k+a 双元音）这类 LLM 生成读音易错的，它们直接决定拼音检索能否命中），随 migration 播种种子词表（目标 1000~2000 条，覆盖八股高频术语、主流中间件/框架/协议/算法名）。
- 术语库启动时全量加载进内存索引（见 4.3），表仅作持久化与维护入口。

### 3.3 运行期上下文（不落新表）

合并后的本场热词列表存入 **graph state**：`InterviewState` 新增字段 `List<String> sessionHotwords`。

**持久化与恢复机制（评审 P1-3，必须照此实现，防静默失效）**：本项目 checkpoint 载体是 `InterviewGraphBuilder` 的 OverAllState——领域对象 `InterviewState` 整体存放于单一 key `STATE_KEY="interviewState"`，由 `MysqlSaver` 按 threadId（= sessionId）持久化，Jackson 序列化携带 `@class` 类型信息可原样恢复。因此 **sessionHotwords 只需作为 InterviewState 的普通字段**，随整体序列化自动持久化/恢复，无需修改 `STATE_KEYS`（那是 OverAllState 顶层 key 的策略列表，`interviewState` 已注册 ReplaceStrategy）。

⚠️ 两个典型错法（均导致断线恢复后热词为空、corpus 偏置**静默失效且无任何报错**）：
1. 把字段挂在 `InterviewSession`（DB 实体）上——DB 实体不参与 graph checkpoint 恢复；
2. 在 OverAllState 顶层新增独立 key 但漏注册 `STATE_KEYS`——不会被 checkpoint 序列化。

一份快照三处消费（corpus / 纠错 Prompt / 评分 Prompt），**每次会话构建快照，禁止全局共享可变状态**（"每请求独立构建纠错上下文"的通用并发原则）。断线重连恢复路径需在验收中显式自测（见第 9 节）。

## 4. 详细设计

### 4.1 会话热词标签化

#### 4.1.1 三个来源

| 来源 | 抽取时机 | 抽取方式 | 成本 |
|---|---|---|---|
| 面试计划 | `PlanGenerator` 生成计划时 | 计划生成 structured output 增加 `hotwords: List<String>` 字段，与计划一次 LLM 调用同时产出 | 零额外调用 |
| 简历 | `ResumeService` Tika 解析出全文后 | 异步线程池调 qwen-turbo 抽取（复用 `VectorizationExecutorConfig` 异步向量化既有模式） | 1 次 turbo 调用/份 |
| JD | `JdService` 保存后 | 同简历 | 1 次 turbo 调用/份 |

抽取 Prompt 要点（宁多勿漏——corpus 软偏置下多词无害，漏词才是损失）：

```
从以下文本中提取所有技术术语，输出 JSON 数组，每项 {term, category}。
类别：framework/middleware/database/algorithm/protocol/language/system/other。
必须覆盖：框架、中间件、数据库、算法、协议、编程语言、云原生组件，
以及候选人项目中的自研系统名/产品名（这类词 ASR 必错且仅此处有）。
英文术语使用官方大小写（如 Redis、Spring Boot）。不要漏，宁多勿少。
```

#### 4.1.2 归一化

参考 `KnowledgePointNormalizer` 既有模式：

- 比对归一：比对时统一小写、去空格/连字符/点号（`SpringBoot` ≡ `spring boot` ≡ `spring-boot`）；
- 规范写法：落库存官方写法（corpus 与 Prompt 消费时用官方大小写，输出侧对下游更友好）；
- 别名映射：`term_dict.aliases` 提供全局别名，会话级首版不做用户自定义别名。

#### 4.1.3 合并与快照

开始面试时（`InterviewService.startInterview`）：`简历 terms ∪ JD terms ∪ 计划 hotwords` → 归一化去重 → 截断上限 100 词（防御异常大词表）→ 写入 session 上下文。

### 4.2 ASR corpus 偏置接入

#### 4.2.1 接入点

`VoiceAsrService.startLocked()` 中 `updateSession` 前一行：

```java
OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
transcriptionParam.setLanguage(cfg.getLanguage());
transcriptionParam.setInputSampleRate(cfg.getSampleRate());
transcriptionParam.setInputAudioFormat(cfg.getFormat());
// 新增：会话热词 corpus 偏置（dashscope-sdk-java 2.22.7 已验证支持）
if (corpusEnabled && StringUtils.isNotBlank(corpusText)) {
    transcriptionParam.setCorpusText(corpusText);   // 序列化为 transcription.corpus.text
}
```

`startTranscription(...)` 签名增加 `String corpusText` 参数（或封装为 `AsrStartOptions`），由 `VoiceInterviewWsHandler.startAsr` 从 session 上下文取热词拼入。拼接格式：逗号分隔的术语串（如 `Redis, MySQL, Spring Boot, Raft, 零拷贝, …`），不附加解释性文字（corpus 越像自然词表越好，减少幻觉诱因）。

#### 4.2.2 corpus 幻觉防御（必做）

已知风险（issue #50）：模型有概率把 corpus 内容整体输出为转写结果。三道防御：

1. **词表克制**：corpus 仅会话热词（≤100 词），从源头控制爆炸半径；
2. **输出侧检测（判据：编辑距离，不用字符重叠率）**：幻觉的特征是**原样输出词表拼接串、无自然语句结构**，因此检测信号为「final 文本与 corpus 拼接串的归一化编辑距离极小（≤0.2）」而非字符重叠率——后者对术语密集句误杀率极高（开场高频句式"我熟悉 Redis、MySQL、Kafka、RabbitMQ、Spring Boot"重叠率轻松超 60%，会被误杀丢整句字幕）；
3. **处置：降级不丢弃**：判定疑似幻觉的 final **照常下发但携带 `suspect: true` 标记**（前端可弱化展示/提示候选人核对），裁决权留给候选人——丢弃字幕违反本方案"不能比没有更差"的总原则；同时记录 `corpus_hallucination` 计数日志。

监控：`corpus_hallucination` 出现即 WARN 日志 + 观测台计数，用于评估当前快照版本（qwen3-asr-flash-realtime-2025-10-27 / -2026-02-10）的实际风险率，必要时一键关闭 `voice.corpus.enabled` 回退。

### 4.3 全局术语库拼音检索

#### 4.3.1 为什么不用语义向量

纠错场景的查询是"语音的另一种写法"：ASR 把 Raft 转成"拉夫特"，两者**语义距离极远、发音距离≈0**。语义向量召回（含本项目 text-embedding-v3）在此场景失效，纠错检索必须按发音（音素/拼音）而非语义进行。中文场景的等价简化物是**拼音**——ASR 的中文错字本身就是同音/近音字，拼音序列几乎不变：

```
入库（离线一次）：Raft   → "la fu te"        零拷贝 → "ling kao bei"
查询（在线）：    "拉夫特" → "la fu te"       ← 编辑距离 0，命中
```

#### 4.3.2 检索实现（内存索引，零中间件）

- 启动时 `term_dict` 全量加载为内存索引：`Map<String, List<Term>>`（pinyin key → 术语，多音字展开多个 key）；
- 查询：对 final 句子滑窗取 n-gram（2~6 字），转拼音后查索引；未命中时对英文片段做编辑距离 ≤1~2 的模糊匹配（覆盖 rediss→Redis 类拼写错误）；
- 召回 top-K（K=20）候选术语，量级千级词表暴力扫描毫秒级完成，**不引入 ES、不建向量**；
- 索引刷新：`term_dict` 变更后手动触发重建（管理接口或重启），术语库是慢变量。

依赖新增：`com.github.promeg:tinypinyin:2.0.3`（纯 Java、无传递依赖、支持多音字）。

### 4.4 后处理纠错服务

#### 4.4.1 触发时序（final 异步，不阻塞字幕）

```
ASR final 事件
  ├─► 立即下发原字幕（现有 sendSubtitle 流程 + seq，交互零延迟）
  └─► 异步提交纠错任务（voice-correction 线程池）
        ├─ 拼音检索召回全局候选 top-20
        ├─ 零候选短路：召回为 0 且句中无英文片段 → 直接结束，不调 LLM
        │   （一场 30 分钟面试几十上百句 final，多数句子无术语错误；
        │    短路可省一半以上 turbo 调用，且 correction 触发率监控更干净）
        ├─ 组装纠错 Prompt（原句 + 会话热词全量 + 全局候选）
        ├─ qwen-turbo 调用（关 thinking，超时 3s）
        ├─ 成功 → diff 计算置信档 → 下发 correction 消息（携带同 seq）
        └─ 失败/超时 → 静默丢弃，字幕保持原文（零降级伤害）
```

以 VAD 句子为 chunk（"单 chunk 失败只回退该 chunk"原则的句级版）：单句纠错失败只影响该句，不累积到整段草稿。

#### 4.4.2 纠错 Prompt（保守原则）

```
你是语音转写纠错器。下面是一句 ASR 转写文本，可能存在同音/近音错字。
[本场面试相关术语表]：{sessionHotwords}
[候选术语（按拼音相似度召回）]：{globalCandidates}

规则：
1. 仅当某词与上述术语表/候选中的术语发音相同或极相近，且替换后句义通顺时，才替换；
2. 严格保持原句语义与语序，禁止改写、扩写、书面化；
3. 保留口语特征（语气词、重复、卡顿）原样不动；
4. 没有把握的位置一律保留原文。
输出 JSON：{"corrections":[{"from":"拉夫特","to":"Raft","confidence":"high|low"}],"text":"修正后全文"}
```

置信分档：`high` = 术语表精确命中（音同且上下文无歧义）→ 前端直接替换 + 高亮；`low` = 仅召回候选、上下文弱 → 前端悬浮候选点选。**最终裁决权始终在候选人**（手动提交环节）。

#### 4.4.3 seq 对齐机制（评审 P1-1，前后端契约）

现状：`sendSubtitle` 只发 `type/text/final`，无序号——correction 异步补发时前端无法定位对应草稿中的哪一句。修订：

- **seq 由 WS Handler 侧统一分配**：`VoiceInterviewWsHandler` 维护 per-session `AtomicInteger`（final 专用），每下发一条 final 字幕递增；**不靠前端自己数**（partial/断线重连/丢弃都会导致前端计数漂移）；
- **subtitle final 消息携带 seq**（partial 为预览性质，不做对齐，不带 seq）；
- correction 消息携带同 seq，前端按 seq 定位草稿句。

```json
{ "type": "subtitle", "sessionId": "...", "seq": 42, "text": "我用过拉夫特做一致性", "final": true, "suspect": false }
```

```json
{ "type": "asr_correction", "sessionId": "...", "seq": 42,
  "text": "我用过 Raft 做一致性",
  "corrections": [{"from":"拉夫特","to":"Raft","confidence":"high","span":[6,10]}] }
```

前端 `InterviewRoomView` 按 seq 定位并按 `span` 高亮改动；`low` 置信度渲染为可点选候选气泡。候选人不操作则按 `text`（high 已替换）累积进草稿。

#### 4.4.4 纠错与手动编辑的竞态规则（评审 P1-4，前端必实现）

correction 异步补发（P95 < 2s）而语音模式下候选人随时在编辑草稿，"high 直接替换"可能覆盖用户输入。前端按 seq 跟踪每句状态，三条规则：

| 句子状态 | 收到 correction 时行为 |
|---|---|
| 未被触碰（纯 ASR 累积） | high：自动替换 + 高亮；low：悬浮候选点选 |
| **已被候选人手动编辑** | 跳过自动替换，仅提示"有可用纠错建议"（点选后才应用）——用户输入永远优先 |
| **所在草稿已提交** | 直接丢弃 correction |

原则：自动替换只作用于"未被触碰过的句子"，人工输入永远优先于机器修正。

#### 4.4.5 可观测性

每次纠错调用走既有 `LlmTraceObservationHandler` 落 `llm_trace`，Agent 归因 `asr-correction`，可核算：纠错调用量、token 成本（预估单句 ≤600 token）、耗时分布、correction 触发率。零候选短路（4.4.1）保证触发率只统计"确有候选却未纠正/纠正失败"的句子，指标语义干净。

### 4.5 评测体系扩展（eval）

#### 4.5.1 噪声注入回归（新规则指标）

对 golden 用例的标准回答文本注入同音错误（按拼音相同/近音常用字替换术语，如 Redis→"瑞迪斯"、Raft→"拉夫特"），同一用例跑干净版 vs 噪声版，输出指标：

- **评分漂移 delta**：|噪声版总分 − 干净版总分|，目标 ≤ 校准阈值（建议初值 0.5 分，随人工标注校准）；
- 用于回归门禁：纠错链路/Prompt 改动后，delta 不得劣化。

#### 4.5.2 干扰测试集门禁（防纠错幻觉）

取干净转写文本直接过纠错链路，**corrections 数必须为 0**（或 ≤ 极小阈值）。任何 Prompt/词库变更跑此门禁，防止"纠错比不纠更差"。

**可复现性要求（评审 P2-6）**：纠错 Prompt 含会话热词表，热词表不同则门禁结果不同。评测用例必须**固定热词表快照**（golden 用例硬编码本场热词，不动态拉取），否则 corrections=0 门禁不可复现。

#### 4.5.3 corpus A/B 对照实验（P1 验收动作）

同一段含 10+ 技术术语的测试音频，corpus 空 vs corpus=会话热词，各跑 N 遍，统计：术语正确率提升幅度、`corpus_hallucination` 发生率。实验结论决定 corpus 默认开关走向，并沉淀进观测台。

## 5. 代码变更清单

### 5.1 新增

| 文件/模块 | 说明 |
|---|---|
| `hotword/Hotword.java` + `HotwordMapper.java` | session_hotword 表实体与 Mapper |
| `hotword/TermExtractService.java` | qwen-turbo 术语抽取（简历/JD 共用），异步执行 |
| `hotword/HotwordService.java` | 归一化、按 source 幂等重建、合并去重、session 快照构建 |
| `voice/correction/TermDict.java` + `TermDictMapper.java` | term_dict 表实体与 Mapper |
| `voice/correction/PinyinTermIndex.java` | 全局术语库内存索引（拼音 key + 多音字展开 + n-gram 查询 + 编辑距离） |
| `voice/correction/AsrCorrectionService.java` | final 纠错编排：检索召回 → Prompt → LLM → diff/置信分档 → WS 下发，失败静默回退 |
| `eval/NoiseInjector.java` + 评分漂移指标 | 噪声注入与回归指标，接入既有规则指标层 |
| Flyway `V1x__session_hotword.sql`、`V1x__term_dict.sql`（含种子词表数据） | 建表与播种 |

### 5.2 修改

| 文件 | 变更 |
|---|---|
| `VoiceAsrService` | `startTranscription/restartTranscription` 增加 corpusText 参数；`startLocked` 中 `setCorpusText`；final 分发处增加 corpus 幻觉检测（编辑距离判据 + suspect 标记，见 4.2.2） |
| `VoiceInterviewWsHandler` | ① `startAsr` 从 session 上下文取热词拼 corpus；② **新增 per-session AtomicInteger，final 字幕消息携带 seq**（4.4.3）；③ onFinal 挂接异步纠错（原字幕下发流程不变）；④ 疑似幻觉 final 携带 suspect 标记下发 |
| `InterviewState`（graph 领域对象） | 新增 `sessionHotwords` 字段（随 STATE_KEY 整体序列化持久化，见 3.3；**不要**挂在 InterviewSession DB 实体或 OverAllState 顶层新 key） |
| `PlanGenerator`（及计划输出模型） | structured output 增加 `hotwords` 字段 |
| `ResumeService` / `JdService` | 解析/保存成功后异步触发 `TermExtractService` |
| `EvaluateNode`、`AskNode`（AnswerEvaluator/ProjectAgent 等 Prompt 组装处） | 注入会话热词表 + "回答经 ASR 转写，术语可能存在同音转写错误"声明 |
| `application.yml` + `VoiceProperties` | 新增 `voice.corpus.*` / `voice.correction.*` 配置 |
| 前端 `InterviewRoomView.vue` | ① correction 按 seq 对齐草稿句；② high 高亮替换、low 候选点选；③ **实现竞态三规则**（手动编辑句只提示、已提交丢弃，见 4.4.4） |
| eval 用例与指标注册 | 噪声注入回归 + 干扰集门禁两项指标；干扰集用例固定热词快照（4.5.2） |

## 6. 配置与开关

```yaml
voice:
  corpus:
    enabled: false          # P1 实验后决定默认值；关闭即完全不传 corpus
    max-terms: 100          # corpus 词表上限（幻觉爆炸半径控制）
    hallucination-edit-distance-threshold: 0.2   # final 与 corpus 拼接串的归一化编辑距离 ≤ 此值判疑似幻觉（不丢弃，suspect 标记下发）
  correction:
    enabled: false          # 后处理纠错总开关
    model: qwen-turbo       # 关 thinking 的轻量模型
    timeout-ms: 3000        # 超时即回退原文
    recall-top-k: 20        # 拼音召回候选数
```

回退语义：任一开关关闭，链路行为与当前版本完全一致；纠错失败永不阻塞字幕与草稿。

## 7. 分阶段落地计划

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **P0（零新增调用）** | PlanGenerator 输出 hotwords；EvaluateNode/AskNode Prompt 注入术语表 + ASR 噪声声明 | 手动面试冒烟：术语类回答评分不再因错字误判 |
| **P1（源头偏置）** | 简历/JD 异步抽取；hotword 表与合并快照；corpus 接入 + 幻觉防御 | corpus A/B 实验（4.5.3）：术语正确率提升、幻觉率可接受 → 开默认 |
| **P2（后处理纠错）** | term_dict 种子词表；PinyinTermIndex；AsrCorrectionService；seq 对齐 + 前端建议式 UI + 竞态三规则 | 干扰集门禁（4.5.2）corrections=0；真实术语录音纠错召回抽检；手动编辑句不被覆盖的交互自测 |
| **P3（评测闭环）** | NoiseInjector + 评分漂移指标进 eval 回归门禁 | golden 用例噪声版回归通过，delta ≤ 阈值 |

P0→P1 无相互依赖可并行；P2 依赖 P1 的热词快照；P3 依赖 P2 的纠错链路。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| corpus 幻觉整体输出（issue #50，已知未完全修复） | 词表 ≤100；编辑距离判据检测（对术语密集句不误杀）+ suspect 标记降级不丢弃字幕；`corpus_hallucination` 监控；开关秒级回退 |
| 幻觉检测误杀正常术语密集句（如"我熟悉 Redis、MySQL、Kafka…"） | 判据用编辑距离而非字符重叠率（4.2.2）；且误判后果仅 suspect 标记（字幕照发），最坏情况 = 现状 |
| 纠错 LLM 引入新错误（越纠越错） | 保守 Prompt 四规则；干扰集零改动门禁；建议式交互人工终审；correction 触发率监控（异常升高即告警） |
| 纠错与手动编辑竞态：high 替换覆盖用户输入 | seq 对齐 + 前端竞态三规则（4.4.4）：手动编辑句只提示不替换、已提交丢弃；人工输入永远优先 |
| 热词快照持久化载体选错 → 断线恢复后 corpus 静默失效 | 载体锁定为 InterviewState 字段随 STATE_KEY 整体序列化（3.3），变更清单明令禁止两个错法；断线恢复 corpus 生效进验收自测 |
| final 纠错延迟感知 | 原字幕先行下发，纠错异步补发；turbo + 关 thinking + 3s 超时 |
| 成本增量 | 零候选短路省一半以上调用；单句 ≤600 token turbo，仅 final 触发；llm_trace 归因 `asr-correction` 可精确核算 |
| 术语库维护成本 | 种子词表一次播种（LLM 从知识库文档批量抽取 + 人工审核，抽查优先英文发音歧义词）；慢变量低频维护；缺词时 P0/P1 的会话热词层仍兜底 |
| 拼音多音字误召回 | 多音字全展开候选 key；编辑距离容忍；召回仅作候选，最终由 LLM 上下文判断 + 候选人确认 |
| ASR 模型快照升级行为变化 | corpus 实验按快照版本记录；升级快照时重跑 A/B 与幻觉观察 |

## 9. 验收指标

| 指标 | 目标 |
|---|---|
| 术语转写正确率（测试音频集） | corpus 开启后提升 ≥30%（A/B 实测，基线以实验报告为准） |
| 干扰测试集纠错改动数 | = 0（门禁，任何词库/Prompt 变更必须通过；用例固定热词快照） |
| 噪声注入评分漂移 | delta ≤ 0.5 分（初值，随校准修订） |
| final 纠错端到端 P95 | < 2s（异步补发，不影响字幕首显） |
| corpus 幻觉发生率 | A/B 实验中 0 出现；线上监控出现即回退评估 |
| 断线恢复后 corpus 热词仍生效 | 恢复自测用例：断连重连后 ASR 会话重建，corpusText 从恢复的 InterviewState.sessionHotwords 重新拼入，术语偏置不丢失 |
| 竞态交互 | 手动编辑过的句子 100% 不被自动替换覆盖（交互自测） |
| 纠错链路失败率 | 无感知回退，字幕/草稿/提交主流程 100% 不受影响 |

---

附：本方案推导过程中的关键事实依据——dashscope-sdk-java 2.22.7 `OmniRealtimeTranscriptionParam#setCorpusText`（源码已验证，注释 "Set text in corpus to improve model recognition accuracy"）；corpus 幻觉 issue：github.com/aliyun/alibabacloud-bailian-speech-demo/issues/50；qwen3-asr-flash-realtime 计费 0.00033 元/秒（华北2）。
