package com.interview.agent.voice.correction;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.ai.LlmCallWrapper;
import com.interview.agent.hotword.HotwordService;
import com.interview.agent.voice.VoiceChannelRegistry;
import com.interview.agent.voice.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * ASR 后处理术语纠错（ASR 热词纠错方案 4.4）：
 * final 定稿后异步执行「拼音检索召回 → LLM 保守纠错 → diff 置信分档 → WS 补发 correction」。
 *
 * <p>可靠性原则（钉钉同款 fail-open）：纠错超时/异常回退原文，一句话都不多——
 * 字幕已先行下发，correction 只是增强补发，任何失败对主流程零伤害。
 * 单句纠错失败只影响该句（VAD 句子为天然 chunk），不累积到整段草稿。
 */
@Service
public class AsrCorrectionService {
    private static final Logger log = LoggerFactory.getLogger(AsrCorrectionService.class);
    private static final Pattern ENGLISH_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+#.\\-]{1,}");

    private final ChatClient chatClient;
    private final PinyinTermIndex termIndex;
    private final VoiceProperties properties;
    private final VoiceChannelRegistry channelRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 纠错专用线程池：final 字幕下发零延迟，纠错在后台补跑 */
    private final ExecutorService correctionExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "voice-correction-" + CORRECTION_THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger CORRECTION_THREAD_SEQ = new AtomicInteger();

    public AsrCorrectionService(ChatClient.Builder chatClientBuilder, PinyinTermIndex termIndex,
                                VoiceProperties properties, VoiceChannelRegistry channelRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.termIndex = termIndex;
        this.properties = properties;
        this.channelRegistry = channelRegistry;
    }

    /** final 定稿后的异步纠错（onFinal 挂接点）：失败静默，字幕/草稿保持原文 */
    public void correctAsync(String sessionId, int seq, String text, List<String> sessionHotwords) {
        VoiceProperties.Correction cfg = properties.getCorrection();
        if (!cfg.isEnabled() || text == null || text.isBlank()) {
            return;
        }
        correctionExecutor.submit(() -> {
            try {
                CorrectionResult result = correctSync(text, sessionHotwords);
                if (result.corrections().isEmpty()) {
                    return;
                }
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "asr_correction");
                msg.put("sessionId", sessionId);
                msg.put("seq", seq);
                msg.put("text", result.text());
                List<Map<String, Object>> corrections = new ArrayList<>();
                for (Correction c : result.corrections()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("from", c.from());
                    item.put("to", c.to());
                    item.put("confidence", c.confidence());
                    corrections.add(item);
                }
                msg.put("corrections", corrections);
                channelRegistry.sendJson(sessionId, msg);
                log.debug("[{}] ASR 纠错补发: seq={}, corrections={}", sessionId, seq, result.corrections().size());
            } catch (Exception e) {
                // 钉钉同款失败语义：静默丢弃纠错，字幕保持原文（零降级伤害）
                log.debug("[{}] ASR 纠错失败（静默回退原文）: seq={}, err={}", sessionId, seq, e.getMessage());
            }
        });
    }

    /**
     * 同步纠错（评测干扰集门禁复用）：拼音召回 → 零候选短路 → LLM 保守纠错 → 置信校准。
     */
    public CorrectionResult correctSync(String text, List<String> sessionHotwords) {
        VoiceProperties.Correction cfg = properties.getCorrection();
        List<String> hotwords = sessionHotwords == null ? List.of() : sessionHotwords;
        // 拼音检索召回全局候选
        List<String> candidates = termIndex.recall(text, cfg.getRecallTopK());
        // 零候选短路：召回为 0 且句中无英文片段 → 不调 LLM（一场面试多数句子无术语错误，
        // 短路可省一半以上 turbo 调用，且 correction 触发率监控更干净）
        if (candidates.isEmpty() && hotwords.isEmpty() && !ENGLISH_TOKEN.matcher(text).find()) {
            return new CorrectionResult(text, List.of());
        }
        return LlmCallWrapper.callWithRetry("asr-correction", () -> {
            String content = chatClient.prompt()
                    .options(DashScopeChatOptions.builder()
                            .withModel(cfg.getModel())
                            .withEnableThinking(false)
                            .withTemperature(0.1)
                            .build())
                    .user(buildPrompt(text, hotwords, candidates))
                    .call()
                    .content();
            return parse(content, text, hotwords, candidates);
        }, () -> new CorrectionResult(text, List.of()),
                Math.max(1, (cfg.getTimeoutMs() + 999) / 1000), 0);
    }

    private String buildPrompt(String text, List<String> sessionHotwords, List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是语音转写纠错器。下面是一句 ASR 转写文本，可能存在同音/近音错字。\n");
        sb.append("[本场面试相关术语表]：").append(sessionHotwords.isEmpty() ? "无" : String.join("、", sessionHotwords)).append("\n");
        sb.append("[候选术语（按拼音相似度召回）]：").append(candidates.isEmpty() ? "无" : String.join("、", candidates)).append("\n\n");
        sb.append("转写文本：").append(text).append("\n\n");
        sb.append("规则：\n");
        sb.append("1. 仅当某词与上述术语表/候选中的术语发音相同或极相近，且替换后句义通顺时，才替换；\n");
        sb.append("2. 严格保持原句语义与语序，禁止改写、扩写、书面化；\n");
        sb.append("3. 保留口语特征（语气词、重复、卡顿）原样不动；\n");
        sb.append("4. 没有把握的位置一律保留原文。\n");
        sb.append("输出 JSON：{\"corrections\":[{\"from\":\"拉夫特\",\"to\":\"Raft\",\"confidence\":\"high|low\"}],\"text\":\"修正后全文\"}。没有需要纠正的就输出空数组。");
        return sb.toString();
    }

    /** 解析纠错结果并做置信校准（AnswerEvaluator 同款花括号截取风格） */
    private CorrectionResult parse(String content, String originalText,
                                   List<String> sessionHotwords, List<String> candidates) {
        try {
            String json = content == null ? "" : content;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("LLM 纠错输出缺少 JSON");
            }
            JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
            String correctedText = node.path("text").asText(originalText);
            List<Correction> corrections = new ArrayList<>();
            JsonNode arr = node.path("corrections");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    String from = item.path("from").asText("");
                    String to = item.path("to").asText("");
                    if (from.isBlank() || to.isBlank() || from.equals(to)) continue;
                    // 置信校准：替换目标必须精确命中（会话热词 ∪ 召回候选）才允许 high，
                    // LLM 自信但目标不在已知术语集内的强制降为 low（前端仅提示候选）
                    String confidence = item.path("confidence").asText("low").toLowerCase(Locale.ROOT);
                    if ("high".equals(confidence) && !isKnownTerm(to, sessionHotwords, candidates)) {
                        confidence = "low";
                    }
                    corrections.add(new Correction(from.trim(), to.trim(), confidence));
                }
            }
            // 修正后全文与原句完全相同视为无纠错
            if (correctedText.equals(originalText) && corrections.isEmpty()) {
                return new CorrectionResult(originalText, List.of());
            }
            return new CorrectionResult(correctedText, corrections);
        } catch (Exception e) {
            // 解析失败视为本次 LLM 调用失败，交由 LlmCallWrapper 重试/降级（降级即原文）
            throw new RuntimeException("LLM 纠错结果解析失败", e);
        }
    }

    private boolean isKnownTerm(String to, List<String> sessionHotwords, List<String> candidates) {
        String key = HotwordService.normalizeKey(to);
        if (key.isEmpty()) return false;
        return sessionHotwords.stream().anyMatch(h -> HotwordService.normalizeKey(h).equals(key))
                || candidates.stream().anyMatch(c -> HotwordService.normalizeKey(c).equals(key));
    }

    /** 单处纠错项 */
    public record Correction(String from, String to, String confidence) {}

    /** 纠错结果：修正后全文 + 纠错明细（评测干扰集门禁统计 corrections 数） */
    public record CorrectionResult(String text, List<Correction> corrections) {}
}
