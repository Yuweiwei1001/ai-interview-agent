-- WebSocket 音译多读法补充（ASR 纠错评测 113 条集验证发现的召回问题）：
-- '维所克特'(wei suo ke te) 与 '维骚克特'(wei sao ke te) 都是面试口语常见读法，
-- 词表原仅登记 'wei sao ke te'，导致说"维所克特"时拼音召回为空。
-- 多读法用 ';' 分隔（PinyinTermIndex.expandPinyinKeys 支持）。
UPDATE term_dict SET pinyin = 'wei sao ke te;wei suo ke te', updated_at = NOW() WHERE term = 'WebSocket';
