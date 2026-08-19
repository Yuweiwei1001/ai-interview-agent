-- 面试会话增加交互模式字段：TEXT（文字面试，默认）/ VOICE（语音面试，启用 Speaker 语音合成）
ALTER TABLE interview_session ADD COLUMN phase VARCHAR(10) NOT NULL DEFAULT 'TEXT' COMMENT '交互模式: TEXT/VOICE' AFTER persona;
