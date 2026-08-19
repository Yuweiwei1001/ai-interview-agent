package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.SpeakerAgent;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.InterviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class AskNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(AskNode.class);
    private final AskQuestionTool askQuestionTool;
    private final SpeakerAgent speakerAgent;

    public AskNode(AskQuestionTool askQuestionTool, SpeakerAgent speakerAgent) {
        this.askQuestionTool = askQuestionTool;
        this.speakerAgent = speakerAgent;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("AskNode: 推送题目并等待回答, round={}, sessionId={}", state.getCurrentRound() + 1, state.getSessionId());

        // 使用 Coordinator 已生成的题目，不覆盖
        String question = state.getCurrentQuestion();
        if (question == null || question.isBlank()) {
            question = "请介绍一下你的技术背景和项目经验。";
            state.setCurrentQuestion(question);
        }

        // 语音模式：TTS 播报必须在推题前触发（虚拟线程异步合成，与 SSE 推题并行，不阻塞图线程）
        speakerAgent.speakIfVoice(state.getSessionId(), state.getPhase(), question);

        // 推送思考中状态
        askQuestionTool.sendThinking(state.getSessionId());

        // 等待回答（阻塞）。题号 = 非追问轮计数 + 1：追问轮不占题号，编程题轮占题号，保证题号连续
        int questionNumber = (int) state.getRounds().stream()
                .filter(r -> !r.isFollowup())
                .count() + 1;
        String answer = askQuestionTool.askAndWait(state.getSessionId(), question, "QUESTION", questionNumber);
        state.setCurrentAnswer(answer);

        // 记录轮次
        state.setCurrentRound(state.getCurrentRound() + 1);

        log.info("AskNode: 收到回答, round={}, answerLength={}", state.getCurrentRound(), answer.length());
        return state;
    }
}
