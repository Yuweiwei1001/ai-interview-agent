-- 面试会话增加"当前待回答题目"字段：SSE 事件丢失时前端轮询可恢复下一题，避免提交后卡死
ALTER TABLE interview_session ADD COLUMN current_question TEXT NULL COMMENT '当前待回答题目（断线恢复用）' AFTER status;
