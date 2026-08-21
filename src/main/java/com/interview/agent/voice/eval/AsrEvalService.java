package com.interview.agent.voice.eval;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.voice.VoiceProperties;
import com.interview.agent.voice.correction.AsrCorrectionService;
import com.interview.agent.voice.correction.AsrCorrectionService.Correction;
import com.interview.agent.voice.correction.AsrCorrectionService.CorrectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * ASR 转写评测（音频文件 → 转写原文 → 术语纠错 → 量化对比）。
 *
 * <p>转写复用生产同款模型 {@code qwen-audio-3.0-asr-flash-streaming}（取自
 * {@link VoiceProperties#getAsr()}，配置驱动），走 DashScope Fun-ASR-Realtime SDK 的
 * {@link Recognition#call} 非流式调用：直接提交本地文件、同步返回完整转写，支持 mp3/wav/aac 等格式，
 * 无需公网 URL、无需自己解码重采样。与生产实时链路（VoiceAsrService）共用同一模型与 API Key，
 * 评测反映的是生产模型真实的转写质量与错误分布。纠错复用 {@link AsrCorrectionService#correctSync} 完整链路。
 *
 * <p>量化：用户可选填期望转写文本，用字符级归一化编辑距离算「转写原文 vs 期望」和
 * 「纠错后 vs 期望」的相似度，并给出 IMPROVED / DEGRADED / NEUTRAL 结论。
 */
@Service
public class AsrEvalService {
    private static final Logger log = LoggerFactory.getLogger(AsrEvalService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VoiceProperties properties;
    private final AsrCorrectionService correctionService;

    public AsrEvalService(VoiceProperties properties, AsrCorrectionService correctionService) {
        this.properties = properties;
        this.correctionService = correctionService;
    }

    /** 一次完整评测：转写 → 纠错 → 量化 */
    public AsrEvalResult eval(byte[] audio, String filename, String expectedText, List<String> hotwords) throws Exception {
        String raw = transcribe(audio, filename);
        CorrectionResult corrected = correctionService.correctSync(raw, hotwords == null ? List.of() : hotwords);

        boolean hasExpected = expectedText != null && !expectedText.isBlank();
        double rawScore = hasExpected ? similarity(raw, expectedText) : -1;
        double correctedScore = hasExpected ? similarity(corrected.text(), expectedText) : -1;
        String verdict;
        if (!hasExpected) {
            verdict = "NO_EXPECTED";
        } else if (correctedScore > rawScore + 1e-6) {
            verdict = "IMPROVED";
        } else if (correctedScore < rawScore - 1e-6) {
            verdict = "DEGRADED";
        } else {
            verdict = "NEUTRAL";
        }
        return new AsrEvalResult(raw, corrected.text(), corrected.corrections(), rawScore, correctedScore, verdict);
    }

    /**
     * 调用生产同款模型转写（Fun-ASR-Realtime 非流式）：上传字节落临时文件后直接提交，
     * 同步返回完整转写文本。模型/API Key/采样率均取自 {@link VoiceProperties}（与生产实时链路一致）。
     */
    private String transcribe(byte[] audio, String filename) throws Exception {
        VoiceProperties.Asr cfg = properties.getAsr();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException("ASR 未配置 api-key");
        }
        String ext = extensionFor(filename);
        File tmp = File.createTempFile("asr-eval-", "." + ext);
        try {
            Files.write(tmp.toPath(), audio);
            RecognitionParam param = RecognitionParam.builder()
                    .model(cfg.getModel())
                    .apiKey(cfg.getApiKey())
                    .format(ext)
                    .sampleRate(cfg.getSampleRate())
                    .build();
            Recognition recognizer = new Recognition();
            try {
                String json = recognizer.call(param, tmp);
                String text = extractText(json);
                log.info("ASR 评测转写完成: {}", text);
                return text;
            } finally {
                try {
                    recognizer.getDuplexApi().close(1000, "bye");
                } catch (Exception e) {
                    log.debug("ASR 评测连接关闭异常（忽略）: {}", e.getMessage());
                }
            }
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    /** 按扩展名推断 Fun-ASR format（支持 pcm/wav/mp3/aac/opus 等；未知默认 mp3） */
    private static String extensionFor(String filename) {
        if (filename == null) return "mp3";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) return "wav";
        if (lower.endsWith(".mp3")) return "mp3";
        if (lower.endsWith(".m4a")) return "m4a";
        if (lower.endsWith(".aac")) return "aac";
        if (lower.endsWith(".opus")) return "opus";
        if (lower.endsWith(".ogg")) return "opus";
        if (lower.endsWith(".pcm")) return "pcm";
        return "mp3";
    }

    /**
     * {@link Recognition#call} 非流式返回的是完整 JSON 事件流（sentences 逐帧渐进累积 +
     * 时间戳/分词），非纯文本。这里按 sentence_id 提取每个句子的最终版 text 并按序拼接；
     * 解析失败回退原始 JSON（后续纠错链路对垃圾输入零候选短路，不产生误伤）。
     */
    private String extractText(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode sentences = root.path("sentences");
            if (!sentences.isArray() || sentences.isEmpty()) {
                return json.trim();
            }
            LinkedHashMap<Integer, String> finalBySentence = new LinkedHashMap<>();
            for (JsonNode s : sentences) {
                if (!s.path("sentence_end").asBoolean(false)) {
                    continue;
                }
                String text = s.path("text").asText("");
                if (!text.isBlank()) {
                    finalBySentence.put(s.path("sentence_id").asInt(0), text);
                }
            }
            if (!finalBySentence.isEmpty()) {
                return String.join("", finalBySentence.values()).trim();
            }
            return json.trim();
        } catch (Exception e) {
            log.warn("ASR 评测转写结果解析失败（回退原文）: {}", e.getMessage());
            return json.trim();
        }
    }

    /** 字符级归一化编辑距离相似度（0~1，1=完全一致；仅保留中英文与数字参与比对） */
    static double similarity(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        int maxLen = Math.max(x.length(), y.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshtein(x, y) / maxLen;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /** 一次评测结果 */
    public record AsrEvalResult(String raw, String corrected, List<Correction> corrections,
                                double rawScore, double correctedScore, String verdict) {}
}
