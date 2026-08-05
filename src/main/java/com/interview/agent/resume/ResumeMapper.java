package com.interview.agent.resume;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ResumeMapper {
    @Insert("INSERT INTO resume(user_id, file_name, file_type, file_size, raw_text, content_hash) VALUES(#{userId}, #{fileName}, #{fileType}, #{fileSize}, #{rawText}, #{contentHash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Resume resume);

    @Select("SELECT * FROM resume WHERE id = #{id}")
    Resume findById(Long id);

    @Select("SELECT * FROM resume WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Resume> findByUserId(Long userId);

    @Delete("DELETE FROM resume WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}