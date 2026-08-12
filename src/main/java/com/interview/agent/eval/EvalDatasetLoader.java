package com.interview.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 评测数据集加载器：从 classpath 读取 golden case 与 judge 校准集。
 * - 用例集：resources/eval/dataset/*.json（每个文件一个 EvalCase）
 * - 校准集：resources/eval/calibration/calibration-set.json（人工标注 QA 样本）
 */
@Component
public class EvalDatasetLoader {
    private static final Logger log = LoggerFactory.getLogger(EvalDatasetLoader.class);
    private final ObjectMapper objectMapper;

    public EvalDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvalCase> loadCases() {
        List<EvalCase> cases = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:eval/dataset/*.json");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    EvalCase evalCase = objectMapper.readValue(in, EvalCase.class);
                    if (evalCase.getCaseId() == null || evalCase.getCaseId().isBlank()) {
                        String name = resource.getFilename() == null ? "unknown" : resource.getFilename();
                        evalCase.setCaseId(name.replace(".json", ""));
                    }
                    cases.add(evalCase);
                } catch (Exception e) {
                    log.warn("评测用例解析失败，跳过: {}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            throw new BaseException("评测数据集加载失败: " + e.getMessage());
        }
        cases.sort(Comparator.comparing(EvalCase::getCaseId));
        return cases;
    }

    /** 校准样本：question + answer + 人工标注档位（EXCELLENT/GOOD/AVERAGE/FAIL） */
    public record CalibrationSample(String question, String answer, String expectedLevel, String note) {}

    public List<CalibrationSample> loadCalibrationSamples() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource("classpath:eval/calibration/calibration-set.json");
            if (!resource.exists()) {
                return List.of();
            }
            try (InputStream in = resource.getInputStream()) {
                List<Map<String, String>> raw = objectMapper.readValue(in, new TypeReference<>() {});
                return raw.stream()
                        .map(m -> new CalibrationSample(
                                m.get("question"), m.get("answer"),
                                m.getOrDefault("expectedLevel", "AVERAGE"), m.get("note")))
                        .toList();
            }
        } catch (Exception e) {
            log.warn("judge 校准集加载失败", e);
            return List.of();
        }
    }

    /** 供 REST 展示用的用例摘要（不暴露完整简历/JD 文本） */
    public Map<String, Object> caseSummary(EvalCase evalCase) {
        return Map.of(
                "caseId", evalCase.getCaseId(),
                "description", evalCase.getDescription() == null ? "" : evalCase.getDescription(),
                "direction", evalCase.getDirection() == null ? "" : evalCase.getDirection(),
                "answerLevel", evalCase.getAnswerLevel(),
                "durationMinutes", evalCase.getDurationMinutes(),
                "codingSubmissions", evalCase.getCodingSubmissions().size()
        );
    }
}
