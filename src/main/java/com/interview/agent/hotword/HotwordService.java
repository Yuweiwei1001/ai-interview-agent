package com.interview.agent.hotword;

import com.interview.agent.interview.plan.InterviewPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话级热词服务（ASR 热词纠错方案 4.1）：
 * 术语抽取结果的幂等落库、归一化去重、面试开始时的会话热词快照构建。
 */
@Service
public class HotwordService {
    private static final Logger log = LoggerFactory.getLogger(HotwordService.class);

    private final HotwordMapper hotwordMapper;

    public HotwordService(HotwordMapper hotwordMapper) {
        this.hotwordMapper = hotwordMapper;
    }

    /** 按来源幂等重建：全删全插（不维护增量 diff）；同源重复上传/重新生成语义正确 */
    public void rebuild(String sourceType, Long sourceId, Long userId, List<ExtractedTerm> terms) {
        hotwordMapper.deleteBySource(sourceType, sourceId);
        if (terms == null || terms.isEmpty()) {
            log.info("热词重建完成（无术语）: source={}:{}, userId={}", sourceType, sourceId, userId);
            return;
        }
        // 归一化去重：同一来源内不同写法（springboot/Spring Boot）只保留首见的规范写法
        Map<String, Hotword> dedup = new LinkedHashMap<>();
        for (ExtractedTerm t : terms) {
            if (t == null || t.term() == null || t.term().isBlank()) continue;
            String term = t.term().trim();
            if (term.length() > 128) continue;
            String key = normalizeKey(term);
            if (key.isEmpty() || dedup.containsKey(key)) continue;
            Hotword hotword = new Hotword();
            hotword.setUserId(userId);
            hotword.setSourceType(sourceType);
            hotword.setSourceId(sourceId);
            hotword.setTerm(term);
            hotword.setCategory(t.category());
            dedup.put(key, hotword);
        }
        dedup.values().forEach(hotwordMapper::insert);
        log.info("热词重建完成: source={}:{}, userId={}, terms={} 条",
                sourceType, sourceId, userId, dedup.size());
    }

    /**
     * 构建本场面试热词快照：简历 terms ∪ JD terms ∪ 计划 hotwords，
     * 归一化去重后截断上限（防御异常大词表，corpus 幻觉爆炸半径控制）。
     * 快照存入 InterviewState 随 checkpoint 持久化，一份快照三处消费（corpus/纠错 Prompt/评分 Prompt）。
     */
    public List<String> buildSessionSnapshot(Long resumeId, Long jdId, InterviewPlan plan, int maxTerms) {
        LinkedHashSet<String> snapshot = new LinkedHashSet<>();
        collectFromSource(snapshot, "resume", resumeId);
        collectFromSource(snapshot, "jd", jdId);
        if (plan != null && plan.getHotwords() != null) {
            for (String hotword : plan.getHotwords()) {
                addNormalized(snapshot, hotword);
            }
        }
        List<String> result = new ArrayList<>(snapshot);
        if (result.size() > maxTerms) {
            result = new ArrayList<>(result.subList(0, maxTerms));
        }
        log.info("会话热词快照构建完成: resumeId={}, jdId={}, planHotwords={}, 合计 {} 条",
                resumeId, jdId,
                plan != null && plan.getHotwords() != null ? plan.getHotwords().size() : 0,
                result.size());
        return result;
    }

    private void collectFromSource(LinkedHashSet<String> target, String sourceType, Long sourceId) {
        if (sourceId == null) return;
        try {
            for (Hotword hotword : hotwordMapper.findBySource(sourceType, sourceId)) {
                addNormalized(target, hotword.getTerm());
            }
        } catch (Exception e) {
            // 热词是增强能力，任一来源缺失/查询失败都不阻断面试启动
            log.warn("热词来源加载失败（跳过该来源）: source={}:{}, err={}", sourceType, sourceId, e.getMessage());
        }
    }

    private void addNormalized(LinkedHashSet<String> target, String term) {
        if (term == null || term.isBlank()) return;
        String key = normalizeKey(term);
        if (key.isEmpty()) return;
        // 以 key 去重但保留原写法：同一 key 已存在则不覆盖（首见优先，来源顺序简历→JD→计划）
        if (target.stream().noneMatch(existing -> normalizeKey(existing).equals(key))) {
            target.add(term.trim());
        }
    }

    /** 比对归一化：小写、去空格/连字符/点号/加号等符号（springboot ≡ spring boot ≡ spring-boot） */
    public static String normalizeKey(String term) {
        if (term == null) return "";
        return term.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }

    /** LLM 术语抽取结果（TermExtractService 结构化输出载体） */
    public record ExtractedTerm(String term, String category) {}
}
