-- ==================== LLM 调用追踪（可观测性 + 成本统计） ====================
-- 每次 ChatModel 调用落一行；成功/失败均记录，重试产生多行（成本按全量求和）。
-- embedding 调用暂不记录（MVP 已知缺口）。
CREATE TABLE llm_trace (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id          VARCHAR(100)  DEFAULT NULL COMMENT '面试会话ID，非面试链路调用为NULL',
    agent               VARCHAR(64)   DEFAULT NULL COMMENT '调用方Agent：technical/project/coding/evaluator等',
    model               VARCHAR(64)   DEFAULT NULL,
    prompt_tokens       INT           NOT NULL DEFAULT 0,
    completion_tokens   INT           NOT NULL DEFAULT 0,
    total_tokens        INT           NOT NULL DEFAULT 0,
    duration_ms         BIGINT        NOT NULL DEFAULT 0 COMMENT '超时后僵尸线程完成的调用可能大于超时阈值',
    status              VARCHAR(16)   NOT NULL DEFAULT 'success' COMMENT 'success/error',
    error_msg           VARCHAR(500)  DEFAULT NULL,
    estimated_cost      DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '估算成本（元），写入时按模型单价计算',
    prompt_excerpt      TEXT          DEFAULT NULL COMMENT 'prompt截断摘录（防表膨胀）',
    completion_excerpt  TEXT          DEFAULT NULL,
    created_at          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_session (session_id),
    INDEX idx_trace_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
