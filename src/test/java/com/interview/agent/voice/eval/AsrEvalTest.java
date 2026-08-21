package com.interview.agent.voice.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ASR 转写评测验证（本地临时音频，人工验收用）。
 * 用法：mvn test -Dtest=AsrEvalTest -DfailIfNoTests=false
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AsrEvalTest {

    @Autowired
    private AsrEvalService asrEvalService;

    @Test
    void evalAudio() throws Exception {
        byte[] wav1 = Files.readAllBytes(Path.of("target/asr-eval-test1.wav"));
        AsrEvalService.AsrEvalResult r1 = asrEvalService.eval(wav1, "asr-eval-test1.wav", "我用Raft做共识算法", List.of());
        System.out.println("### test1 [拉夫特] raw=" + r1.raw() + " corrected=" + r1.corrected()
                + " corrections=" + r1.corrections() + " rawScore=" + r1.rawScore()
                + " correctedScore=" + r1.correctedScore() + " verdict=" + r1.verdict());

        byte[] wav2 = Files.readAllBytes(Path.of("target/asr-eval-test2.wav"));
        AsrEvalService.AsrEvalResult r2 = asrEvalService.eval(wav2, "asr-eval-test2.wav", "高并发下要防缓存雪崩", List.of());
        System.out.println("### test2 [学崩] raw=" + r2.raw() + " corrected=" + r2.corrected()
                + " corrections=" + r2.corrections() + " rawScore=" + r2.rawScore()
                + " correctedScore=" + r2.correctedScore() + " verdict=" + r2.verdict());
    }
}
