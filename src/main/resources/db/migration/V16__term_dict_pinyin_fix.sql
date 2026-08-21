-- 词表 pinyin 数据修正（ASR 纠错评测 38 条集验证发现的召回/置信问题）：
-- 1) ReAct 改用音译 'rui ai ke te'（瑞艾克特）：面试口语最常用音译读音，与 React 拼音撞车，
--    由 LLM 语义裁决按上下文区分（"推理框架"→ReAct，"前端框架"→React），评测已验证可区分；
-- 2) '调用/调度' 中 '调' 读 diao（xi tong diao yong / jin cheng diao du），原文 tiao 为笔误，
--    会导致"系统掉用→系统调用"类同音句召回失败；
-- 3) 补插缺失术语 '微内核'（"微内盒→微内核" 同音召回）。
UPDATE term_dict SET pinyin = 'rui ai ke te', updated_at = NOW() WHERE term = 'ReAct';
UPDATE term_dict SET pinyin = 'xi tong diao yong', updated_at = NOW() WHERE term = '系统调用';
UPDATE term_dict SET pinyin = 'jin cheng diao du', updated_at = NOW() WHERE term = '进程调度';
INSERT IGNORE INTO term_dict (term, pinyin, category, aliases) VALUES ('微内核', 'wei nei he', 'system', '[]');
