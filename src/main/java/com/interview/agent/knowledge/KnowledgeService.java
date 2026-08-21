package com.interview.agent.knowledge;

import com.interview.agent.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识库服务：知识库/文档 CRUD + 异步切分向量化（ES 存储）。
 * 借鉴 ThinkVerse KnowledgeServiceImpl 的 ETL 与并发控制逻辑。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final VectorStore vectorStore;
    private final Executor vectorizationExecutor;

    /** 文档级向量化锁：串行化同一文档的向量化任务，避免并发删/增向量产生 ES 脏数据 */
    private final ConcurrentHashMap<Long, Object> docVectorizeLocks = new ConcurrentHashMap<>();

    public KnowledgeService(KnowledgeBaseMapper kbMapper,
                            KnowledgeDocumentMapper docMapper,
                            VectorStore vectorStore,
                            @Qualifier("vectorizationExecutor") Executor vectorizationExecutor) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.vectorStore = vectorStore;
        this.vectorizationExecutor = vectorizationExecutor;
    }

    public KnowledgeBase createKb(String name, String description, Long userId) {
        if (name == null || name.isBlank()) {
            throw new BaseException("知识库名称不能为空");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name.trim());
        kb.setDescription(description);
        kb.setUserId(userId);
        kb.setDocumentCount(0);
        kbMapper.insert(kb);
        return kb;
    }

    public List<KnowledgeBase> listKb(Long userId) {
        return kbMapper.findByUserId(userId);
    }

    public KnowledgeBase getKb(Long id, Long userId) {
        KnowledgeBase kb = kbMapper.findById(id);
        if (kb == null) {
            throw new BaseException("知识库不存在");
        }
        if (!kb.getUserId().equals(userId)) {
            throw new BaseException("无权访问此知识库");
        }
        return kb;
    }

    public void deleteKb(Long id, Long userId) {
        getKb(id, userId);
        // 先删 ES 中该知识库的所有向量，再删文档与知识库记录
        deleteKbVectors(id);
        docMapper.deleteByKbId(id);
        kbMapper.deleteByIdAndUserId(id, userId);
    }

    public KnowledgeDocument addDocument(Long kbId, String title, String contentMd, boolean vectorize, Long userId) {
        getKb(kbId, userId); // 权限校验
        if (title == null || title.isBlank()) {
            throw new BaseException("文档标题不能为空");
        }
        if (contentMd == null || contentMd.isBlank()) {
            throw new BaseException("文档内容不能为空");
        }

        // 落库：vectorize 决定初始状态（VECTORIZING / DRAFT）
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(kbId);
        doc.setTitle(title.trim());
        doc.setContentMd(contentMd);
        doc.setChunkCount(0);
        doc.setStatus(vectorize ? "VECTORIZING" : "DRAFT");
        docMapper.insert(doc);
        kbMapper.incrementDocumentCount(kbId, 1);

        if (vectorize) {
            Long docId = doc.getId();
            String docTitle = title.trim();
            vectorizationExecutor.execute(() -> vectorizeAsync(kbId, docId, docTitle, contentMd, null));
            log.info("知识库文档已保存，向量化异步执行: kbId={}, docId={}", kbId, docId);
        } else {
            log.info("知识库文档已保存（仅保存，未向量化）: kbId={}, docId={}", kbId, doc.getId());
        }
        return doc;
    }

    public List<KnowledgeDocument> listDocuments(Long kbId, Long userId) {
        getKb(kbId, userId); // 权限校验
        return docMapper.findByKbId(kbId);
    }

    public KnowledgeDocument getDocument(Long kbId, Long docId, Long userId) {
        getKb(kbId, userId); // 权限校验
        KnowledgeDocument doc = docMapper.findById(docId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(kbId)) {
            throw new BaseException("文档不存在");
        }
        return doc;
    }

    public KnowledgeDocument updateDocument(Long kbId, Long docId, String title, String contentMd,
                                            boolean vectorize, Long userId) {
        getKb(kbId, userId); // 权限校验
        KnowledgeDocument doc = docMapper.findById(docId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(kbId)) {
            throw new BaseException("文档不存在");
        }
        if (title == null || title.isBlank()) {
            throw new BaseException("文档标题不能为空");
        }
        if (contentMd == null || contentMd.isBlank()) {
            throw new BaseException("文档内容不能为空");
        }

        String oldTitle = doc.getTitle(); // 先捕获原标题（向量以其存储，删除时用于标题兜底）
        doc.setTitle(title.trim());
        doc.setContentMd(contentMd);

        if (vectorize) {
            // 保存并向量化：置 VECTORIZING，异步删旧向量 + 重新向量化
            doc.setStatus("VECTORIZING");
            docMapper.update(doc);
            String docTitle = title.trim();
            vectorizationExecutor.execute(() -> vectorizeAsync(kbId, docId, docTitle, contentMd, oldTitle));
            log.info("知识库文档已更新，向量重建异步执行: kbId={}, docId={}", kbId, docId);
        } else {
            // 仅保存：置 DRAFT 并删除已有向量，使其不可被面试检索
            // 加锁与异步向量化互斥，避免设为 DRAFT 后被在途异步任务覆盖成 ACTIVE
            Object lock = docVectorizeLocks.computeIfAbsent(docId, k -> new Object());
            synchronized (lock) {
                doc.setStatus("DRAFT");
                docMapper.update(doc);
                deleteDocVectors(kbId, docId, oldTitle);
            }
            log.info("知识库文档已更新（仅保存，转为 DRAFT）: kbId={}, docId={}", kbId, docId);
        }
        return doc;
    }

    public void deleteDocument(Long kbId, Long docId, Long userId) {
        getKb(kbId, userId); // 权限校验
        KnowledgeDocument doc = docMapper.findById(docId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(kbId)) {
            throw new BaseException("文档不存在");
        }
        // 加锁与异步向量化互斥，避免删除后异步任务写入孤儿向量
        Object lock = docVectorizeLocks.computeIfAbsent(docId, k -> new Object());
        synchronized (lock) {
            deleteDocVectors(kbId, docId, doc.getTitle());
            docMapper.deleteById(docId);
        }
        kbMapper.incrementDocumentCount(kbId, -1);
        // 清理文档级锁，避免内存泄漏
        docVectorizeLocks.remove(docId);
    }

    /**
     * 异步向量化：删除旧向量（仅更新场景）→ 切分 + 向量化 → 回填 chunkCount 并置 ACTIVE；失败置 FAILED。
     * oldTitle 为 null 表示新增场景（无旧向量可删）。
     */
    private void vectorizeAsync(Long kbId, Long docId, String title, String contentMd, String oldTitle) {
        // 同文档加锁：连续保存同一文档时串行执行（后保存者覆盖先保存者），避免并发删/增向量交错产生脏数据
        Object lock = docVectorizeLocks.computeIfAbsent(docId, k -> new Object());
        synchronized (lock) {
            try {
                // 守卫：若文档已删除或已被并发更新转为 DRAFT，则放弃向量化
                KnowledgeDocument current = docMapper.findById(docId);
                if (current == null || "DRAFT".equals(current.getStatus())) {
                    log.info("文档已删除或转为 DRAFT，跳过向量化: docId={}", docId);
                    return;
                }
                if (oldTitle != null) {
                    deleteDocVectors(kbId, docId, oldTitle);
                }
                List<Document> chunks = splitAndEmbed(kbId, docId, title, contentMd);
                // 仅回填 chunkCount + status，避免全字段 update 将后台线程的旧快照覆盖用户新修改
                KnowledgeDocument upd = new KnowledgeDocument();
                upd.setId(docId);
                upd.setChunkCount(chunks.size());
                upd.setStatus("ACTIVE");
                docMapper.updateVectorizationResult(upd);
                log.info("文档向量化完成: kbId={}, docId={}, chunks={}", kbId, docId, chunks.size());
            } catch (Exception e) {
                log.error("文档向量化失败: kbId={}, docId={}", kbId, docId, e);
                try {
                    KnowledgeDocument upd = new KnowledgeDocument();
                    upd.setId(docId);
                    upd.setStatus("FAILED");
                    docMapper.updateVectorizationResult(upd);
                } catch (Exception ce) {
                    log.error("更新向量化失败状态异常: docId={}", docId, ce);
                }
            }
        }
    }

    /**
     * ETL 核心：文档切分 + 向量化 + 存入 ES。
     * chunk metadata 含 kbId / docId / title / chunkIndex，docId 用于更新时精确删除旧向量。
     *
     * <p>父子块（Parent-Child）策略：
     * <ul>
     *   <li>child：精细检索单位——按 markdown 标题切成小节（小节过长再固定窗口细分，重叠 20%），存进 ES 向量化，保证语义聚焦；</li>
     *   <li>parent：送 LLM 的完整上下文——整篇文档，不冗余存 ES（避免多一份向量、也避免检索到整篇干扰结果），由检索器从 DB 按 docId 加载。</li>
     * </ul>
     */
    private List<Document> splitAndEmbed(Long kbId, Long docId, String title, String contentMd) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kbId", kbId);
        metadata.put("docId", docId);
        metadata.put("title", title);

        // 仅存 child 小节；parent（整篇）走 DB 加载，见 KnowledgeRetriever
        List<Document> chunks = splitChildren(contentMd, 500, 100).stream()
                .map(text -> new Document(text, new HashMap<>(metadata)))
                .collect(Collectors.toList());

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("kbId", kbId);
            chunk.getMetadata().put("docId", docId);
            chunk.getMetadata().put("title", title);
            chunk.getMetadata().put("chunkIndex", i);
        }

        // 存入 VectorStore（自动调用 EmbeddingModel 向量化）
        vectorStore.add(chunks);
        return chunks;
    }

    /** markdown 标题行（一级到六级 #） */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+");

    /**
     * 结构感知切分：优先按 markdown 标题切成小节作为 child；小节超过 chunkLen 再固定窗口细分；
     * 无标题（或标题只出现在开头、切分无效）时回退为整篇固定窗口切分。
     */
    private List<String> splitChildren(String text, int chunkLen, int overlap) {
        List<String> sections = splitBySections(text);
        if (sections.size() >= 2) {
            List<String> out = new ArrayList<>();
            for (String s : sections) {
                if (s.length() <= chunkLen) {
                    out.add(s);
                } else {
                    out.addAll(splitFixedWindow(s, chunkLen, overlap));
                }
            }
            return out;
        }
        return splitFixedWindow(text, chunkLen, overlap);
    }

    /** 按 markdown 标题把文本切成若干小节，标题与其后内容绑定；无标题时返回空列表 */
    private List<String> splitBySections(String text) {
        List<String> sections = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sections;
        }
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (HEADING_PATTERN.matcher(line).find()) {
                if (current.length() > 0) {
                    sections.add(current.toString().trim());
                }
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            sections.add(current.toString().trim());
        }
        sections.removeIf(String::isBlank);
        return sections;
    }

    /**
     * 固定长度字符切分：窗口 chunkLen、相邻窗口重叠 overlap 字符（即步长 chunkLen-overlap）。
     * 通过 end=min(start+chunkLen, len) 把文本末尾并入最后一个窗口，不产生遗漏；无需再单独追加碎尾。
     */
    private List<String> splitFixedWindow(String text, int chunkLen, int overlap) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parts;
        }
        int stride = Math.max(1, chunkLen - overlap);
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + chunkLen, len);
            parts.add(text.substring(start, end));
            start += stride;
        }
        return parts;
    }

    /** 删除知识库的所有向量（通过 filter 搜索再批量删除） */
    private void deleteKbVectors(Long kbId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        deleteVectorsByExpression(b.eq("kbId", kbId).build(), "kbId=" + kbId);
    }

    /**
     * 删除文档向量（双保险）：1. 按 kbId+docId 过滤；2. 按旧标题兜底（历史存量可能无 docId）。
     * 使用 FilterExpressionBuilder 程序化构建过滤条件，避免字符串拼接的注入风险。
     */
    private void deleteDocVectors(Long kbId, Long docId, String title) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        deleteVectorsByExpression(
                b.and(b.eq("kbId", kbId), b.eq("docId", docId)).build(),
                "kbId=" + kbId + ", docId=" + docId);
        if (title != null && !title.isBlank()) {
            deleteVectorsByExpression(
                    b.and(b.eq("kbId", kbId), b.eq("title", title)).build(),
                    "kbId=" + kbId + ", title=" + title);
        }
    }

    /** 按过滤表达式搜索向量并批量删除。topK(1000) 为单次删除上限，单文档分片数远低于该阈值。 */
    private void deleteVectorsByExpression(Filter.Expression expression, String desc) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query("*").topK(1000)
                            .filterExpression(expression).build());
            if (docs != null && !docs.isEmpty()) {
                List<String> ids = docs.stream().map(Document::getId).toList();
                vectorStore.delete(ids);
                log.info("已删除向量: {}, count={}", desc, ids.size());
            }
        } catch (Exception e) {
            log.error("删除向量失败: {}", desc, e);
        }
    }
}
