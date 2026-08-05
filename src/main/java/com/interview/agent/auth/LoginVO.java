package com.interview.agent.auth;

public class LoginVO {
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private String username;

    public LoginVO(String accessToken, String refreshToken, Long userId, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.username = username;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
}