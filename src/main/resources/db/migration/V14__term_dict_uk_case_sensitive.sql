-- 修正 term_dict 唯一索引大小写敏感性（ASR 热词纠错方案 3.2）
-- 背景：uk_term 默认跟随 utf8mb4_unicode_ci（大小写不敏感），导致 'React' 与 'ReAct'
--       被判定为同一 term，ReAct 无法插入（INSERT IGNORE 被跳过）。
-- 修复：将 term 列排序规则改为 utf8mb4_bin（二进制排序，大小写敏感），
--       重新创建唯一索引（索引继承列 collation），术语名本就应区分大小写。
ALTER TABLE term_dict MODIFY term VARCHAR(128) NOT NULL COLLATE utf8mb4_bin COMMENT '规范术语，如 Raft、零拷贝、MVCC';
ALTER TABLE term_dict ADD UNIQUE KEY uk_term (term);

-- 补插 ReAct（字母名读音，避免与前端 React(rui ai ke te) 拼音撞车）
INSERT IGNORE INTO term_dict (term, pinyin, category, aliases) VALUES
('ReAct', 'ru yi e xi ti', 'ai', '["react","react推理"]');
