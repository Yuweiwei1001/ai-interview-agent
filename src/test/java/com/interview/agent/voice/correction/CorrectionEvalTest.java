package com.interview.agent.voice.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ASR 术语纠错评测（真实链路：拼音召回 + LLM 保守裁决 + 置信校准）。
 *
 * <p>评测集：src/test/resources/correction-eval/cases.json
 * <ul>
 *   <li>positive：术语句（含同音/音译错误），期望纠出 from→to</li>
 *   <li>negative：干扰句，期望 0 纠错（防越纠越错门禁）</li>
 * </ul>
 *
 * <p>指标：Precision = TP/(TP+FP)（FP=干扰句被误纠）；Recall = TP/(TP+FN)（FN=该纠未纠）；
 * F1 = 2PR/(P+R)。LLM 有概率波动，本测试只输出报告、不硬断言（评测性质，供人工验收）。
 *
 * <p>用法（项目根目录，需 DB + API Key 走 application-local.yml）：
 * <pre>
 *   mvn test -Dtest=CorrectionEvalTest -DfailIfNoTests=false
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class CorrectionEvalTest {

    @Autowired
    private AsrCorrectionService correctionService;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void runCorrectionEval() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("correction-eval/cases.json")) {
            if (in == null) throw new IllegalStateException("找不到评测集 correction-eval/cases.json");
            root = om.readTree(in);
        }
        List<JsonNode> positive = new ArrayList<>();
        root.path("positive").forEach(positive::add);
        List<JsonNode> negative = new ArrayList<>();
        root.path("negative").forEach(negative::add);

        int tp = 0, fn = 0, fp = 0;
        long start = System.currentTimeMillis();

        System.out.println("===== 术语句评测（positive " + positive.size() + " 条）=====");
        for (JsonNode c : positive) {
            String text = c.path("text").asText();
            String expFrom = c.path("from").asText();
            String expTo = c.path("to").asText();
            List<AsrCorrectionService.Correction> corrections =
                    correctionService.correctSync(text, List.of()).corrections();
            boolean hit = corrections.stream()
                    .anyMatch(x -> matchesExpected(x, expFrom, expTo));
            System.out.println("[" + (hit ? "PASS" : "FAIL") + "] " + text
                    + "  期望(" + expFrom + "→" + expTo + ") 实际=" + corrections);
            if (hit) tp++; else fn++;
        }

        System.out.println("===== 干扰句评测（negative " + negative.size() + " 条，期望 0 纠错）=====");
        for (JsonNode c : negative) {
            String text = c.path("text").asText();
            List<AsrCorrectionService.Correction> corrections =
                    correctionService.correctSync(text, List.of()).corrections();
            boolean clean = corrections.isEmpty();
            System.out.println("[" + (clean ? "PASS" : "FAIL") + "] " + text + "  实际=" + corrections);
            if (!clean) fp++;
        }

        long cost = System.currentTimeMillis() - start;
        double precision = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

        System.out.println("===== 汇总 =====");
        System.out.printf("TP=%d  FN=%d  FP(干扰集误伤)=%d  耗时 %.0fs%n", tp, fn, fp, cost / 1000.0);
        System.out.printf("Precision=%.1f%%   Recall=%.1f%%   F1=%.1f%%%n",
                precision * 100, recall * 100, f1 * 100);
        System.out.printf("干扰集门禁: %d/%d 被误纠（期望 0）%n", fp, negative.size());
    }

    /** 纠错与期望等价：精确相等，或 LLM 只纠出核心词（"对烈→队列" ⊂ 期望"消息对烈→消息队列"）。
     *  英文术语大小写不敏感（"nginx" ≡ "Nginx"，ASR 场景大小写无关紧要） */
    private boolean matchesExpected(AsrCorrectionService.Correction c, String expFrom, String expTo) {
        if (c.from().equals(expFrom) && c.to().equalsIgnoreCase(expTo)) return true;
        return c.from().length() < expFrom.length()
                && expFrom.contains(c.from())
                && c.to().length() < expTo.length()
                && expTo.toLowerCase(Locale.ROOT).contains(c.to().toLowerCase(Locale.ROOT));
    }
}
