package com.interview.agent.chat;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    @Insert("INSERT INTO chat_session(user_id, title) VALUES(#{userId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ChatSession session);

    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} ORDER BY updated_at DESC, id DESC")
    List<ChatSession> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM chat_session WHERE id = #{id}")
    ChatSession findById(@Param("id") Long id);

    @Update("UPDATE chat_session SET title = #{title} WHERE id = #{id}")
    void updateTitle(@Param("id") Long id, @Param("title") String title);

    @Delete("DELETE FROM chat_session WHERE id = #{id}")
    void deleteById(@Param("id") Long id);
}
