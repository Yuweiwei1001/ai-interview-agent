-- ==================== llm_trace 轮次串联与检索 span 扩展 ====================
-- trace_id：一轮面试的关联 ID（CoordinatorNode 派发新题时生成，追问轮沿用主轮），
--           把同轮的出题/检索/评分/追问多次调用串成一条链路。
-- kind：llm = LLM 调用；retrieval = 知识库向量检索 span（token/成本恒为 0）。
-- eval_score：评分回写——EvaluateNode 评分完成后按 trace_id 回写本轮调整分。
ALTER TABLE llm_trace
    ADD COLUMN trace_id   VARCHAR(64) DEFAULT NULL COMMENT '轮次关联ID：同轮多次调用共享（llm/retrieval 通用）' AFTER session_id,
    ADD COLUMN kind       VARCHAR(16) NOT NULL DEFAULT 'llm' COMMENT 'llm=LLM调用 / retrieval=知识库检索span' AFTER agent,
    ADD COLUMN eval_score INT         DEFAULT NULL COMMENT '评分回写：本轮评估调整分（按 trace_id 更新）',
    ADD INDEX idx_trace_trace_id (trace_id);
