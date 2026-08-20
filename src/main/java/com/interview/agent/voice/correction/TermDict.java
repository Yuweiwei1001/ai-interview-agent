package com.interview.agent.voice.correction;

import java.time.LocalDateTime;

/** 全局计算机术语词典条目（拼音检索用，不向量化） */
public class TermDict {
    private Long id;
    /** 规范术语，如 Raft、零拷贝、MVCC */
    private String term;
    /** 空格分隔读音序列，多音字用 | 分隔候选 */
    private String pinyin;
    private String category;
    /** JSON 数组字符串：别名/常见错误写法 */
    private String aliases;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public String getPinyin() { return pinyin; }
    public void setPinyin(String pinyin) { this.pinyin = pinyin; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
