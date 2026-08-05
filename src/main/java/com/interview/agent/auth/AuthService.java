package com.interview.agent.auth;

import com.interview.agent.common.exception.BaseException;
import com.interview.agent.common.utils.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public LoginVO register(UserRegisterDTO dto) {
        if (userMapper.findByUsername(dto.getUsername()) != null) {
            throw new BaseException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setRole("user");
        user.setStatus("active");
        userMapper.insert(user);

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        return new LoginVO(accessToken, refreshToken, user.getId(), user.getUsername());
    }

    public LoginVO login(UserLoginDTO dto) {
        User user = userMapper.findActiveByUsername(dto.getUsername());
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BaseException("用户名或密码错误");
        }
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        return new LoginVO(accessToken, refreshToken, user.getId(), user.getUsername());
    }

    public LoginVO refresh(String refreshToken) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(refreshToken);
            User user = userMapper.findById(userId);
            if (user == null) {
                throw new BaseException("用户不存在");
            }
            String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
            String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());
            return new LoginVO(newAccessToken, newRefreshToken, user.getId(), user.getUsername());
        } catch (Exception e) {
            throw new BaseException("刷新 Token 无效或已过期");
        }
    }
}