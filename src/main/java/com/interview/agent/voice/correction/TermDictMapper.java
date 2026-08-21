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

    /** 管理端：列出全部词条（含 disabled，便于前端维护开关） */
    @Select("SELECT * FROM term_dict ORDER BY id DESC")
    List<TermDict> findAll();

    @Select("SELECT * FROM term_dict WHERE id = #{id}")
    TermDict findById(Long id);

    @Select("SELECT * FROM term_dict WHERE term = #{term}")
    TermDict findByTerm(String term);

    @Update("UPDATE term_dict SET term = #{term}, pinyin = #{pinyin}, category = #{category}, " +
            "aliases = #{aliases}, enabled = #{enabled} WHERE id = #{id}")
    int update(TermDict termDict);

    @Delete("DELETE FROM term_dict WHERE id = #{id}")
    int deleteById(Long id);
}
