package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.SpeakerAgent;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.InterviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class FollowUpNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(FollowUpNode.class);
    private final AskQuestionTool askQuestionTool;
    private final SpeakerAgent speakerAgent;

    public FollowUpNode(AskQuestionTool askQuestionTool, SpeakerAgent speakerAgent) {
        this.askQuestionTool = askQuestionTool;
        this.speakerAgent = speakerAgent;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("FollowUpNode: 追问, round={}, sessionId={}", state.getCurrentRound(), state.getSessionId());
        String followUp = state.getPendingFollowUp();
        if (followUp == null || followUp.isBlank()) {
            state.setIsFollowUpRound(false);
            return state;
        }
        state.setIsFollowUpRound(true);
        state.setCurrentQuestion(followUp);
        // 语音模式：追问同样 TTS 播报（推题前异步触发）
        speakerAgent.speakIfVoice(state.getSessionId(), state.getPhase(), followUp);
        askQuestionTool.sendThinking(state.getSessionId());
        String answer = askQuestionTool.askAndWait(state.getSessionId(), followUp, "FOLLOW_UP", state.getCurrentRound());
        state.setCurrentAnswer(answer);
        state.setPendingFollowUp(null);
        return state;
    }
}