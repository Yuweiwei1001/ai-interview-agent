package com.interview.agent.voice.correction;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TermDictMapper {
    @Insert("INSERT INTO term_dict(term, pinyin, category, aliases) VALUES(#{term}, #{pinyin}, #{category}, #{aliases})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TermDict termDict);

    /** 启动时全量加载构建内存索引；索引重建（管理触发）时也走此方法 */
    @Select("SELECT * FROM term_dict WHERE enabled = 1")
    List<TermDict> findAllEnabled();

    @Select("SELECT * FROM term_dict WHERE term = #{term}")
    TermDict findByTerm(String term);
}
