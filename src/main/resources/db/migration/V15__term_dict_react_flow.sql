-- 全局计算机术语词典 三期扩充：React/Agent 工作流组件词（ASR 热词纠错方案）
-- 补充 plan / tool use / tool call / function call / 检索 / query；并为 RAG、Agent 补充中文使用者口语读音。
-- 拼音规则：完整读音变体用 ; 分隔（PinyinTermIndex.expandPinyinKeys 支持），音节级多音字仍用 | 分隔。
-- 英文短语（tool use 等）以中文常用说法做拼音 key（如 工具使用 gong ju shi yong），用户说中文也能召回纠成规范英文术语。

INSERT IGNORE INTO term_dict (term, pinyin, category, aliases) VALUES
('plan',          'pu lan',                               'ai', '["plan","计划"]'),
('tool use',      'tu er you si;gong ju shi yong',        'ai', '["tool use","工具使用"]'),
('tool call',     'tu er kao er;gong ju diao yong',       'ai', '["tool call","工具调用"]'),
('function call', 'fang ke shen kao er;han shu diao yong', 'ai', '["function call","函数调用"]'),
('检索',          'jian suo',                             'ai', '["检索","retrieval"]'),
('query',         'kui rui',                              'ai', '["query","查询"]');

-- RAG 补充口语读音 re ge（/ræg/），保留字母读法 ar ei ji
UPDATE term_dict SET pinyin = 'ar ei ji;re ge', updated_at = NOW() WHERE term = 'RAG';
-- Agent 补充常见音译 ei zhen te（诶真特），保留 ei jian te
UPDATE term_dict SET pinyin = 'ei jian te;ei zhen te', updated_at = NOW() WHERE term = 'Agent';
