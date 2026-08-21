package com.interview.agent.voice.correction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 术语词库（term_dict）管理服务：CRUD + 变更后触发内存拼音索引重建。
 *
 * <p>词库是慢变量（纠错链路运行时以启动重建的内存索引为据），但管理端手动增删改
 * 后若等到重启才生效不符合「前端可维护」的诉求，故每次写操作后调用
 * {@link PinyinTermIndex#rebuild()} 同步重建（千级词表毫秒级，足够安全）。
 * 重建失败时保留旧索引，纠错链路不受影响（fail-open）。
 */
@Service
public class TermDictService {
    private static final Logger log = LoggerFactory.getLogger(TermDictService.class);

    private final TermDictMapper termDictMapper;
    private final PinyinTermIndex termIndex;
    private final ObjectMapper objectMapper;

    public TermDictService(TermDictMapper termDictMapper, PinyinTermIndex termIndex, ObjectMapper objectMapper) {
        this.termDictMapper = termDictMapper;
        this.termIndex = termIndex;
        this.objectMapper = objectMapper;
    }

    /** 管理端列表：含 disabled 词条，按 id 倒序（新增在前） */
    public List<TermDict> list() {
        return termDictMapper.findAll();
    }

    public TermDict getById(Long id) {
        TermDict dict = termDictMapper.findById(id);
        if (dict == null) {
            throw new BaseException("词条不存在");
        }
        return dict;
    }

    @Transactional
    public TermDict create(SaveDTO dto) {
        validate(dto);
        if (termDictMapper.findByTerm(dto.getTerm().trim()) != null) {
            throw new BaseException("术语已存在：" + dto.getTerm().trim());
        }
        TermDict dict = new TermDict();
        apply(dict, dto);
        termDictMapper.insert(dict);
        rebuildSafely();
        return termDictMapper.findById(dict.getId());
    }

    @Transactional
    public TermDict update(Long id, SaveDTO dto) {
        validate(dto);
        TermDict dict = termDictMapper.findById(id);
        if (dict == null) {
            throw new BaseException("词条不存在");
        }
        // 改名场景校验唯一性（排除自身）
        TermDict sameTerm = termDictMapper.findByTerm(dto.getTerm().trim());
        if (sameTerm != null && !sameTerm.getId().equals(id)) {
            throw new BaseException("术语已存在：" + dto.getTerm().trim());
        }
        apply(dict, dto);
        termDictMapper.update(dict);
        rebuildSafely();
        return termDictMapper.findById(id);
    }

    @Transactional
    public void delete(Long id) {
        termDictMapper.deleteById(id);
        rebuildSafely();
    }

    private void apply(TermDict dict, SaveDTO dto) {
        dict.setTerm(dto.getTerm().trim());
        dict.setPinyin(dto.getPinyin().trim());
        dict.setCategory(trimToNull(dto.getCategory()));
        dict.setAliases(toAliasesJson(dto.getAliases()));
        dict.setEnabled(dto.getEnabled() == null || dto.getEnabled());
    }

    private void validate(SaveDTO dto) {
        if (dto == null) {
            throw new BaseException("参数不能为空");
        }
        if (dto.getTerm() == null || dto.getTerm().isBlank()) {
            throw new BaseException("术语不能为空");
        }
        if (dto.getTerm().trim().length() > 128) {
            throw new BaseException("术语长度不能超过 128 字符");
        }
        if (dto.getPinyin() == null || dto.getPinyin().isBlank()) {
            throw new BaseException("拼音不能为空");
        }
        if (dto.getPinyin().trim().length() > 256) {
            throw new BaseException("拼音长度不能超过 256 字符");
        }
    }

    /** 别名列表序列化为 JSON 数组字符串（与 DB 列注释一致），空列表存 NULL */
    private String toAliasesJson(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(aliases);
        } catch (Exception e) {
            throw new BaseException("别名格式不合法");
        }
    }

    /** 索引重建失败不阻断写库（词库以 DB 为准，索引下次写操作或重启会再对账） */
    private void rebuildSafely() {
        try {
            termIndex.rebuild();
        } catch (Exception e) {
            log.warn("术语索引重建失败（本次写库已成功，索引稍后对账）: {}", e.getMessage());
        }
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 新建/编辑共用入参 */
    public static class SaveDTO {
        private String term;
        private String pinyin;
        private String category;
        /** 别名/常见错误写法列表，存库时序列化为 JSON 数组 */
        private List<String> aliases;
        /** 默认 true；false 表示停用（不参与纠错召回） */
        private Boolean enabled;

        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }
        public String getPinyin() { return pinyin; }
        public void setPinyin(String pinyin) { this.pinyin = pinyin; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public List<String> getAliases() { return aliases; }
        public void setAliases(List<String> aliases) { this.aliases = aliases; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}