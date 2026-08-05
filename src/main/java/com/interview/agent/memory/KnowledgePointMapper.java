package com.interview.agent.memory;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KnowledgePointMapper {
    @Insert("INSERT INTO knowledge_point(user_id, topic, status, confidence, last_assessed, assessment_count, verified) " +
            "VALUES(#{userId}, #{topic}, #{status}, #{confidence}, #{lastAssessed}, #{assessmentCount}, #{verified}) " +
            "ON DUPLICATE KEY UPDATE status=#{status}, confidence=#{confidence}, last_assessed=#{lastAssessed}, " +
            "assessment_count=assessment_count+1, updated_at=NOW()")
    void upsert(KnowledgePoint point);

    @Select("SELECT * FROM knowledge_point WHERE user_id = #{userId} AND topic = #{topic}")
    KnowledgePoint findByUserIdAndTopic(@Param("userId") Long userId, @Param("topic") String topic);

    @Select("SELECT * FROM knowledge_point WHERE user_id = #{userId} AND status = 'weak' ORDER BY confidence ASC LIMIT #{limit}")
    List<KnowledgePoint> findWeakPoints(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM knowledge_point WHERE user_id = #{userId} ORDER BY last_assessed ASC LIMIT #{limit}")
    List<KnowledgePoint> findLeastAssessed(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM knowledge_point WHERE user_id = #{userId} ORDER BY topic")
    List<KnowledgePoint> findByUserId(Long userId);
}