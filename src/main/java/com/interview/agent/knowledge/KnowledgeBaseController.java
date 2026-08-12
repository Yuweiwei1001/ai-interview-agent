package com.interview.agent.knowledge;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库 REST API：知识库 CRUD + 文档增删改查（支持保存并向量化）。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeService knowledgeService;

    public KnowledgeBaseController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    // ---------- 知识库 ----------

    @PostMapping
    public Result<KnowledgeBase> create(@RequestBody KbCreateDTO dto) {
        return Result.success(knowledgeService.createKb(dto.getName(), dto.getDescription(), BaseContext.getCurrentId()));
    }

    @GetMapping
    public Result<List<KnowledgeBase>> list() {
        return Result.success(knowledgeService.listKb(BaseContext.getCurrentId()));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBase> getById(@PathVariable Long id) {
        return Result.success(knowledgeService.getKb(id, BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.deleteKb(id, BaseContext.getCurrentId());
        return Result.success();
    }

    // ---------- 文档 ----------

    @PostMapping("/{kbId}/documents")
    public Result<KnowledgeDocument> addDocument(@PathVariable Long kbId, @RequestBody DocCreateDTO dto) {
        return Result.success(knowledgeService.addDocument(
                kbId, dto.getTitle(), dto.getContentMd(), Boolean.TRUE.equals(dto.getVectorize()),
                BaseContext.getCurrentId()));
    }

    @GetMapping("/{kbId}/documents")
    public Result<List<KnowledgeDocument>> listDocuments(@PathVariable Long kbId) {
        return Result.success(knowledgeService.listDocuments(kbId, BaseContext.getCurrentId()));
    }

    @GetMapping("/{kbId}/documents/{docId}")
    public Result<KnowledgeDocument> getDocument(@PathVariable Long kbId, @PathVariable Long docId) {
        return Result.success(knowledgeService.getDocument(kbId, docId, BaseContext.getCurrentId()));
    }

    @PutMapping("/{kbId}/documents/{docId}")
    public Result<KnowledgeDocument> updateDocument(@PathVariable Long kbId, @PathVariable Long docId,
                                                    @RequestBody DocCreateDTO dto) {
        return Result.success(knowledgeService.updateDocument(
                kbId, docId, dto.getTitle(), dto.getContentMd(), Boolean.TRUE.equals(dto.getVectorize()),
                BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{kbId}/documents/{docId}")
    public Result<Void> deleteDocument(@PathVariable Long kbId, @PathVariable Long docId) {
        knowledgeService.deleteDocument(kbId, docId, BaseContext.getCurrentId());
        return Result.success();
    }

    // ---------- DTO ----------

    public static class KbCreateDTO {
        private String name;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class DocCreateDTO {
        private String title;
        private String contentMd;
        /** true=保存并向量化；false/null=仅保存为草稿 */
        private Boolean vectorize;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContentMd() { return contentMd; }
        public void setContentMd(String contentMd) { this.contentMd = contentMd; }
        public Boolean getVectorize() { return vectorize; }
        public void setVectorize(Boolean vectorize) { this.vectorize = vectorize; }
    }
}
