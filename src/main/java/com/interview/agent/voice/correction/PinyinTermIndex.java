package com.interview.agent.voice.correction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.promeg.pinyinhelper.Pinyin;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局术语库拼音内存索引（ASR 热词纠错方案 4.3）。
 *
 * <p>为什么不用语义向量：纠错场景的查询是"语音的另一种写法"——ASR 把 Raft 转成"拉夫特"，
 * 两者语义距离极远、发音距离≈0。中文场景的等价简化物是拼音，不引入 ES、不建向量。
 *
 * <p>启动时 term_dict 全量加载为内存索引（千级词表暴力扫描毫秒级）；
 * 词表是慢变量，变更后重启或手动触发 {@link #rebuild()} 重建。
 */
@Component
public class PinyinTermIndex {
    private static final Logger log = LoggerFactory.getLogger(PinyinTermIndex.class);

    /** 中文滑窗 n-gram 长度范围：术语最短 2 字、最长 6 字（词表术语普遍 ≤6 字） */
    private static final int NGRAM_MIN = 2;
    private static final int NGRAM_MAX = 6;
    /** 英文 token 编辑距离容忍（覆盖 rediss→Redis 类拼写错误）；过短 token 误召回率高，仅 ≥4 参与模糊匹配 */
    private static final int ENGLISH_FUZZY_MAX_DIST = 2;
    private static final int ENGLISH_FUZZY_MIN_LEN = 4;
    /** 多音字候选组合展开上限（防御异常数据导致组合爆炸） */
    private static final int PINYIN_EXPAND_LIMIT = 64;
    /** 归一化 key 数超过该阈值时英文模糊匹配改用 BK 树（否则全表扫描）。当前千级词表全表毫秒级，BK 构建成本不划算 */
    private static final int BK_TREE_THRESHOLD = 5000;

    private static final Pattern CHINESE_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");
    private static final Pattern ENGLISH_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+#.\\-]{1,}");

    private final TermDictMapper termDictMapper;
    private final ObjectMapper objectMapper;

    /** 拼音 key（空格分隔读音，如 "la fu te"）→ 规范术语列表 */
    private volatile Map<String, List<String>> pinyinIndex = Map.of();
    /** 归一化 term/alias（如 "springboot"）→ 规范术语列表（英文模糊匹配用） */
    private volatile Map<String, List<String>> normalizedIndex = Map.of();
    /** 大规模词表时的 BK 树索引（词表 ≤ 阈值时为 null，走全表扫描） */
    private volatile BkTree bkTree;

    public PinyinTermIndex(TermDictMapper termDictMapper, ObjectMapper objectMapper) {
        this.termDictMapper = termDictMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            rebuild();
        } catch (Exception e) {
            // 表不存在（首次启动 Flyway 尚未执行）或加载失败：索引留空，纠错链路零候选短路，主流程不受影响
            log.warn("术语库索引初始化失败（纠错链路降级为无候选）: {}", e.getMessage());
        }
    }

    /** 全量重建索引（term_dict 变更后由管理动作触发；失败保留旧索引） */
    public synchronized void rebuild() {
        List<TermDict> all = termDictMapper.findAllEnabled();
        Map<String, List<String>> pinyin = new LinkedHashMap<>();
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (TermDict dict : all) {
            String term = dict.getTerm();
            if (term == null || term.isBlank()) continue;
            for (String key : expandPinyinKeys(dict.getPinyin())) {
                pinyin.computeIfAbsent(key, k -> new ArrayList<>()).add(term);
            }
            addNormalized(normalized, term, term);
            for (String alias : parseAliases(dict.getAliases())) {
                addNormalized(normalized, alias, term);
            }
        }
        this.pinyinIndex = pinyin;
        this.normalizedIndex = normalized;
        // 英文模糊匹配策略：词表超阈值构建 BK 树剪枝，否则全表扫描（千级毫秒级）
        this.bkTree = normalized.size() > BK_TREE_THRESHOLD ? BkTree.of(normalized.keySet()) : null;
        log.info("术语库索引已加载: 术语 {} 条, 拼音 key {} 个, 归一化 key {} 个{}",
                all.size(), pinyin.size(), normalized.size(),
                bkTree != null ? ", 英文模糊匹配已切换 BK 树(" + bkTree.size() + " 词)" : "");
    }

    /**
     * 召回候选术语：中文 n-gram 拼音精确命中 + 英文 token 编辑距离模糊匹配。
     * 返回按命中顺序去重的 top-K 规范术语。
     */
    public List<String> recall(String text, int topK) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> hits = new LinkedHashSet<>();
        // 中文滑窗：对 2~6 字片段转拼音查索引
        Matcher cnMatcher = CHINESE_RUN.matcher(text);
        while (cnMatcher.find() && hits.size() < topK) {
            String run = cnMatcher.group();
            for (int n = NGRAM_MIN; n <= NGRAM_MAX && hits.size() < topK; n++) {
                for (int i = 0; i + n <= run.length(); i++) {
                    String key = Pinyin.toPinyin(run.substring(i, i + n), " ");
                    List<String> terms = pinyinIndex.get(key);
                    if (terms != null) {
                        hits.addAll(terms);
                    }
                }
            }
        }
        // 英文 token：先精确（归一化）后模糊（编辑距离，仅长 token）
        Matcher enMatcher = ENGLISH_TOKEN.matcher(text);
        while (enMatcher.find() && hits.size() < topK) {
            String token = normalizeKey(enMatcher.group());
            if (token.isEmpty()) continue;
            List<String> exact = normalizedIndex.get(token);
            if (exact != null) {
                hits.addAll(exact);
                continue;
            }
            if (token.length() >= ENGLISH_FUZZY_MIN_LEN) {
                if (bkTree != null) {
                    // 大规模词表：BK 树三角不等式剪枝，避免全表 O(N) 编辑距离扫描
                    for (String candidate : bkTree.search(token, ENGLISH_FUZZY_MAX_DIST)) {
                        hits.addAll(normalizedIndex.getOrDefault(candidate, List.of()));
                        if (hits.size() >= topK) break;
                    }
                } else {
                    // 千级词表：全表扫描毫秒级，BK 树构建成本反而不划算
                    for (Map.Entry<String, List<String>> entry : normalizedIndex.entrySet()) {
                        if (entry.getKey().length() >= ENGLISH_FUZZY_MIN_LEN
                                && EditDistance.distance(token, entry.getKey()) <= ENGLISH_FUZZY_MAX_DIST) {
                            hits.addAll(entry.getValue());
                            if (hits.size() >= topK) break;
                        }
                    }
                }
            }
        }
        return List.copyOf(hits);
    }

    /** 术语是否在索引中精确存在（纠错置信校准用：替换目标必须命中已知术语） */
    public boolean containsExact(String term) {
        if (term == null || term.isBlank()) return false;
        return normalizedIndex.containsKey(normalizeKey(term));
    }

    public int size() {
        return normalizedIndex.size();
    }

    // ---------- 内部 ----------

    private void addNormalized(Map<String, List<String>> index, String keyTerm, String canonical) {
        String key = normalizeKey(keyTerm);
        if (key.isEmpty()) return;
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(canonical);
    }

    /**
     * 展开 pinyin 字段为全部候选读音 key：完整读音变体用 ; 分隔，音节用空格分隔，多音字候选用 | 分隔。
     * 如 "zhong | chong liang" → ["zhong liang", "chong liang"]；
     * 如 "ar ei ji;re ge"（RAG 两种读法）→ ["ar ei ji", "re ge"]。
     */
    private List<String> expandPinyinKeys(String pinyin) {
        if (pinyin == null || pinyin.isBlank()) return List.of();
        List<String> keys = new ArrayList<>();
        for (String variant : pinyin.split(";")) {
            List<List<String>> syllables = new ArrayList<>();
            for (String s : variant.trim().split("\\s+")) {
                String[] candidates = s.split("\\|");
                List<String> opts = new ArrayList<>();
                for (String c : candidates) {
                    if (!c.isBlank()) opts.add(c.trim());
                }
                if (!opts.isEmpty()) syllables.add(opts);
            }
            expandRecursive(syllables, 0, new StringBuilder(), keys);
        }
        return keys;
    }

    private void expandRecursive(List<List<String>> syllables, int pos, StringBuilder current, List<String> out) {
        if (out.size() >= PINYIN_EXPAND_LIMIT) return;
        if (pos == syllables.size()) {
            if (!current.isEmpty()) out.add(current.toString());
            return;
        }
        for (String opt : syllables.get(pos)) {
            int mark = current.length();
            if (mark > 0) current.append(' ');
            current.append(opt);
            expandRecursive(syllables, pos + 1, current, out);
            current.setLength(mark);
        }
    }

    private List<String> parseAliases(String aliasesJson) {
        if (aliasesJson == null || aliasesJson.isBlank()) return List.of();
        try {
            List<String> list = objectMapper.readValue(aliasesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 归一化 key：小写、去空格/连字符/点号/加号等符号（springboot ≡ spring boot ≡ spring-boot） */
    static String normalizeKey(String term) {
        if (term == null) return "";
        return term.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }
}
