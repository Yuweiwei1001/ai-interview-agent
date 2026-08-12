package com.interview.agent.knowledge;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper {

    @Insert("INSERT INTO knowledge_base(name, description, user_id, document_count) " +
            "VALUES(#{name}, #{description}, #{userId}, #{documentCount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(KnowledgeBase kb);

    @Select("SELECT * FROM knowledge_base WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<KnowledgeBase> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM knowledge_base WHERE id = #{id}")
    KnowledgeBase findById(@Param("id") Long id);

    @Delete("DELETE FROM knowledge_base WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE knowledge_base SET document_count = document_count + #{delta} WHERE id = #{id}")
    void incrementDocumentCount(@Param("id") Long id, @Param("delta") int delta);
}
