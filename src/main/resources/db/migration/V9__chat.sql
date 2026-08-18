-- ==================== 知识笔记 AI 问答 ====================
-- 会话与消息：多轮对话历史完整落库，按用户隔离（所有接口校验 session.userId）
CREATE TABLE chat_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL DEFAULT '新对话',
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_chat_session_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT       NOT NULL,
    role            VARCHAR(20)  NOT NULL COMMENT 'user | assistant',
    content         MEDIUMTEXT   NOT NULL,
    sources         TEXT         DEFAULT NULL COMMENT 'assistant 消息的引用来源 JSON：[{docId,title,excerpt}]',
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_message_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
