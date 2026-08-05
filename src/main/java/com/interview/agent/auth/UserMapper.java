package com.interview.agent.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Insert("INSERT INTO app_user(username, email, password_hash, role, status) VALUES(#{username}, #{email}, #{passwordHash}, #{role}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(User user);

    @Select("SELECT * FROM app_user WHERE username = #{username}")
    User findByUsername(String username);

    @Select("SELECT * FROM app_user WHERE id = #{id}")
    User findById(Long id);

    @Select("SELECT * FROM app_user WHERE username = #{username} AND status = 'active'")
    User findActiveByUsername(String username);
}