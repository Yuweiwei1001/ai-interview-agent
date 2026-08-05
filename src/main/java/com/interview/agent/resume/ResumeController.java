package com.interview.agent.resume;

import com.interview.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/upload")
    public Result<ResumeUploadVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(resumeService.upload(file));
    }

    @GetMapping
    public Result<List<Resume>> list() {
        return Result.success(resumeService.list());
    }

    @GetMapping("/{id}")
    public Result<Resume> getById(@PathVariable Long id) {
        return Result.success(resumeService.getById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }
}