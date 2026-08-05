package com.interview.agent.auth;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.success(authService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody UserLoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BaseException("无效的 Authorization 头");
        }
        String refreshToken = authHeader.substring(7);
        return Result.success(authService.refresh(refreshToken));
    }

    @GetMapping("/me")
    public Result<User> me() {
        return Result.success(null);
    }
}