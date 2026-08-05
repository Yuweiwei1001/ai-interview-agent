package com.interview.agent.jd;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface JdMapper {
    @Insert("INSERT INTO job_description(user_id, title, raw_text, source_url) VALUES(#{userId}, #{title}, #{rawText}, #{sourceUrl})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Jd jd);

    @Select("SELECT * FROM job_description WHERE id = #{id}")
    Jd findById(Long id);

    @Select("SELECT * FROM job_description WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Jd> findByUserId(Long userId);

    @Delete("DELETE FROM job_description WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
