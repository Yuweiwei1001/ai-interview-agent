package com.interview.agent.hotword;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface HotwordMapper {
    @Insert("INSERT INTO session_hotword(user_id, source_type, source_id, term, category) " +
            "VALUES(#{userId}, #{sourceType}, #{sourceId}, #{term}, #{category})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Hotword hotword);

    /** 同一来源重复上传/重新生成时先全删（简单幂等，不维护增量 diff） */
    @Delete("DELETE FROM session_hotword WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    int deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("SELECT * FROM session_hotword WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    List<Hotword> findBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);
}
