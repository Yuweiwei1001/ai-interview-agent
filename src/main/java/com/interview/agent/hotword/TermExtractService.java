package com.interview.agent.hotword;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 技术术语抽取（ASR 热词纠错方案 4.1.1）：qwen-turbo 从简历/JD 文本抽取术语，异步执行。
 *
 * <p>抽取 Prompt 要点：宁多勿漏——corpus 软偏置下多词无害，漏词才是损失。
 * 上传与面试之间天然有时间差，异步抽取不阻塞上传接口。
 */
@Service
public class TermExtractService {
    private static final Logger log = LoggerFactory.getLogger(TermExtractService.class);
    /** 单份文本长度上限：超长简历截断（turbo 术语抽取不需要全文） */
    private static final int MAX_TEXT_LENGTH = 8000;
    private static final int EXTRACTION_TIMEOUT_SECONDS = 20;

    private final ChatClient chatClient;
    private final HotwordService hotwordService;
    /** 抽取专用线程池：与上传请求线程解耦，失败仅记日志（热词缺失有计划/来源兜底） */
    private final ExecutorService extractExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "hotword-extract-" + HOTWORD_THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger HOTWORD_THREAD_SEQ = new AtomicInteger();

    public TermExtractService(ChatClient.Builder chatClientBuilder, HotwordService hotwordService) {
        this.chatClient = chatClientBuilder.build();
        this.hotwordService = hotwordService;
    }

    /** 异步抽取并落库（上传/保存成功后触发） */
    public void extractAsync(String sourceType, Long sourceId, Long userId, String text) {
        if (text == null || text.isBlank()) return;
        String excerpt = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
        extractExecutor.submit(() -> {
            try {
                List<HotwordService.ExtractedTerm> terms = extract(sourceType, sourceId, excerpt);
                hotwordService.rebuild(sourceType, sourceId, userId, terms);
            } catch (Exception e) {
                log.warn("热词抽取失败（跳过，不影响主流程）: source={}:{}, err={}", sourceType, sourceId, e.getMessage());
            }
        });
    }

    /** 同步抽取（评测门禁/管理用途复用） */
    public List<HotwordService.ExtractedTerm> extract(String sourceType, Long sourceId, String text) {
        return LlmCallWrapper.callWithRetry("hotword-extract", () -> {
            ExtractionResult result = chatClient.prompt()
                    .options(DashScopeChatOptions.builder()
                            .withModel("qwen-turbo")
                            .withEnableThinking(false)
                            .withTemperature(0.1)
                            .build())
                    .user(buildPrompt(text))
                    .call()
                    .entity(ExtractionResult.class);
            return result == null || result.terms() == null ? List.<HotwordService.ExtractedTerm>of() : result.terms();
        }, List::of, EXTRACTION_TIMEOUT_SECONDS, 1);
    }

    private String buildPrompt(String text) {
        return "从以下文本中提取所有技术术语，输出 JSON，格式 {\"terms\": [{\"term\": \"...\", \"category\": \"...\"}]}。\n"
                + "类别取值：framework/middleware/database/algorithm/protocol/language/system/other。\n"
                + "必须覆盖：框架、中间件、数据库、算法、协议、编程语言、云原生组件，"
                + "以及文本中出现的自研系统名/产品名（这类词 ASR 必错且仅此处有）。\n"
                + "英文术语使用官方大小写（如 Redis、Spring Boot）。不要漏，宁多勿少。\n\n"
                + "文本：\n" + text;
    }

    /** 结构化输出载体 */
    record ExtractionResult(List<HotwordService.ExtractedTerm> terms) {}
}
