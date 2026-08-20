-- 会话级热词：简历/JD/面试计划抽取的技术术语（ASR 热词纠错方案 3.1）
-- 同一来源重复上传/重新生成时按 (source_type, source_id) 全删全插（简单幂等，不维护增量 diff）
CREATE TABLE session_hotword (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL COMMENT 'app_user.id',
    source_type  VARCHAR(10)  NOT NULL COMMENT 'resume / jd / plan',
    source_id    BIGINT       NOT NULL COMMENT 'resume.id / jd.id / interview_plan 所在 session.id',
    term         VARCHAR(128) NOT NULL COMMENT '归一化后的规范写法，如 Redis、Spring Boot',
    category     VARCHAR(32)  DEFAULT NULL COMMENT 'framework / middleware / database / algorithm / protocol / language / system / other',
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source_term (source_type, source_id, term),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='会话级热词：简历/JD/面试计划抽取的技术术语';
