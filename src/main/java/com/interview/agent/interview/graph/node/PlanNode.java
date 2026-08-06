package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.interview.plan.PlanGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class PlanNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(PlanNode.class);
    private final PlanGenerator planGenerator;

    public PlanNode(PlanGenerator planGenerator) {
        this.planGenerator = planGenerator;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        if (state.getPlan() != null) {
            log.info("PlanNode: 已有面试计划，跳过生成, sessionId={}", state.getSessionId());
            applyMaxRounds(state, state.getPlan());
            return state;
        }
        log.info("PlanNode: 生成面试计划, sessionId={}", state.getSessionId());
        InterviewPlan plan = planGenerator.generatePlan(
                state.getResumeText(),
                state.getJdText(),
                state.getDirection(),
                state.getPersona(),
                state.getDurationMinutes()
        );
        state.setPlan(plan);
        applyMaxRounds(state, plan);
        return state;
    }

    /** 计划的预计总轮次作为最大轮次生效（此前 maxRounds 恒为 20，计划形同虚设） */
    private void applyMaxRounds(com.interview.agent.interview.graph.InterviewState state, InterviewPlan plan) {
        if (plan != null && plan.getEstimatedTotalRounds() > 0) {
            state.setMaxRounds(plan.getEstimatedTotalRounds());
            log.info("PlanNode: 应用计划轮次 maxRounds={}, sessionId={}", plan.getEstimatedTotalRounds(), state.getSessionId());
        }
    }
}
