package com.interview.agent.jd;

import com.interview.agent.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jds")
public class JdController {
    private final JdService jdService;

    public JdController(JdService jdService) {
        this.jdService = jdService;
    }

    @PostMapping
    public Result<Jd> create(@Valid @RequestBody JdCreateDTO dto) {
        return Result.success(jdService.create(dto));
    }

    @GetMapping
    public Result<List<Jd>> list() {
        return Result.success(jdService.list());
    }

    @GetMapping("/{id}")
    public Result<Jd> getById(@PathVariable Long id) {
        return Result.success(jdService.getById(id));
    }

    @PutMapping("/{id}")
    public Result<Jd> update(@PathVariable Long id, @Valid @RequestBody JdCreateDTO dto) {
        return Result.success(jdService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        jdService.delete(id);
        return Result.success();
    }
}
