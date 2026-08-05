package com.interview.agent.interview.controller;

import com.interview.agent.common.result.Result;
import com.interview.agent.interview.plan.PlanGenerator;
import com.interview.agent.interview.plan.PlanRequestDTO;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.resume.ResumeService;
import com.interview.agent.jd.JdService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final PlanGenerator planGenerator;
    private final ResumeService resumeService;
    private final JdService jdService;

    public InterviewController(PlanGenerator planGenerator, ResumeService resumeService, JdService jdService) {
        this.planGenerator = planGenerator;
        this.resumeService = resumeService;
        this.jdService = jdService;
    }

    @PostMapping("/plan")
    public Result<InterviewPlan> createPlan(@Valid @RequestBody PlanRequestDTO dto) {
        String resumeText = null;
        if (dto.getResumeId() != null) {
            resumeText = resumeService.getById(dto.getResumeId()).getRawText();
        }
        String jdText = null;
        if (dto.getJdId() != null) {
            jdText = jdService.getById(dto.getJdId()).getRawText();
        }
        InterviewPlan plan = planGenerator.generatePlan(
                resumeText, jdText, dto.getDirection(), dto.getPersona(), dto.getDurationMinutes());
        return Result.success(plan);
    }
}
