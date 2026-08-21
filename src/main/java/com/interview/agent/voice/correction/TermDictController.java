package com.interview.agent.voice.correction;

import com.interview.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 术语词库 REST API：ASR 纠错词表的增删改查。
 *
 * <p>词库为全局共享（无用户维度），改独立于会话热词（session_hotword，
 * 后者随简历/JD 自动抽取，不可手动维护）。写操作落库后立即重建内存拼音索引，纠错即刻生效。
 */
@RestController
@RequestMapping("/api/term-dict")
public class TermDictController {

    private final TermDictService termDictService;

    public TermDictController(TermDictService termDictService) {
        this.termDictService = termDictService;
    }

    @GetMapping
    public Result<List<TermDict>> list() {
        return Result.success(termDictService.list());
    }

    @GetMapping("/{id}")
    public Result<TermDict> getById(@PathVariable Long id) {
        return Result.success(termDictService.getById(id));
    }

    @PostMapping
    public Result<TermDict> create(@RequestBody TermDictService.SaveDTO dto) {
        return Result.success(termDictService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<TermDict> update(@PathVariable Long id, @RequestBody TermDictService.SaveDTO dto) {
        return Result.success(termDictService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        termDictService.delete(id);
        return Result.success();
    }
}