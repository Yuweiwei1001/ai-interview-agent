-- ==================== 知识库 ====================
CREATE TABLE knowledge_base (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    user_id         BIGINT       NOT NULL,
    document_count  INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 知识文档 ====================
CREATE TABLE knowledge_document (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id   BIGINT       NOT NULL,
    title               VARCHAR(200) NOT NULL,
    content_md          MEDIUMTEXT   NOT NULL,
    chunk_count         INT          NOT NULL DEFAULT 0,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kd_kb (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 面试会话关联知识库 ====================
ALTER TABLE interview_session ADD COLUMN knowledge_base_id BIGINT DEFAULT NULL;
