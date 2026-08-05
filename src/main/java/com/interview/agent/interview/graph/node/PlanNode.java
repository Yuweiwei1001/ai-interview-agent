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
        return state;
    }
}
