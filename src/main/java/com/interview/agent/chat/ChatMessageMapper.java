package com.interview.agent.chat;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    @Insert("INSERT INTO chat_message(session_id, role, content, sources) VALUES(#{sessionId}, #{role}, #{content}, #{sources})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ChatMessage message);

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<ChatMessage> findBySessionId(@Param("sessionId") Long sessionId);

    @Select("SELECT COUNT(*) FROM chat_message WHERE session_id = #{sessionId}")
    int countBySessionId(@Param("sessionId") Long sessionId);

    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    void deleteBySessionId(@Param("sessionId") Long sessionId);
}
