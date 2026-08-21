package com.interview.agent.voice.eval;

import com.interview.agent.common.result.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

/**
 * ASR 转写评测 REST API：上传音频 → 转写 → 术语纠错 → 量化对比。
 *
 * <p>用于人工验收「音频 → ASR 转写 → 纠错」端到端效果。音频限短音频（≤3 分钟，
 * 非实时转写同步调用时长限制）；可选填期望转写文本自动量化（相似度 + 改善/退化结论）。
 */
@RestController
@RequestMapping("/api/voice")
public class AsrEvalController {

    private final AsrEvalService asrEvalService;

    public AsrEvalController(AsrEvalService asrEvalService) {
        this.asrEvalService = asrEvalService;
    }

    @PostMapping(value = "/asr-eval", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AsrEvalService.AsrEvalResult> eval(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "expectedText", required = false) String expectedText,
            @RequestParam(value = "hotwords", required = false) String hotwords) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传音频文件");
        }
        List<String> hw = hotwords == null || hotwords.isBlank() ? List.of()
                : Arrays.stream(hotwords.split("[,，]"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        return Result.success(asrEvalService.eval(file.getBytes(), file.getOriginalFilename(), expectedText, hw));
    }
}
