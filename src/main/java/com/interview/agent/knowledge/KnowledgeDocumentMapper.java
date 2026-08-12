package com.interview.agent.knowledge;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper {

    @Insert("INSERT INTO knowledge_document(knowledge_base_id, title, content_md, chunk_count, status) " +
            "VALUES(#{knowledgeBaseId}, #{title}, #{contentMd}, #{chunkCount}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(KnowledgeDocument doc);

    @Select("SELECT id, knowledge_base_id, title, chunk_count, status, created_at, updated_at " +
            "FROM knowledge_document WHERE knowledge_base_id = #{kbId} ORDER BY created_at DESC")
    List<KnowledgeDocument> findByKbId(@Param("kbId") Long kbId);

    @Select("SELECT * FROM knowledge_document WHERE id = #{id}")
    KnowledgeDocument findById(@Param("id") Long id);

    @Update("UPDATE knowledge_document SET title=#{title}, content_md=#{contentMd}, status=#{status} WHERE id=#{id}")
    void update(KnowledgeDocument doc);

    /** 仅回填向量化结果（chunkCount + status），避免全字段 update 覆盖用户并发修改 */
    @Update("UPDATE knowledge_document SET chunk_count=#{chunkCount}, status=#{status} WHERE id=#{id}")
    void updateVectorizationResult(KnowledgeDocument doc);

    @Delete("DELETE FROM knowledge_document WHERE id = #{id}")
    void deleteById(@Param("id") Long id);

    @Delete("DELETE FROM knowledge_document WHERE knowledge_base_id = #{kbId}")
    void deleteByKbId(@Param("kbId") Long kbId);
}
