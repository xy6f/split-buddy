package com.splitbuddy.module.user.controller;

import com.splitbuddy.common.Result;
import com.splitbuddy.module.user.dto.AuthDto;
import com.splitbuddy.module.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（计划书第 7 章 #1 #2）：SecurityConfig 中放行为 permitAll。
 * Controller 保持薄：只做绑定 + 校验触发 + 转发，无业务逻辑。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody AuthDto.RegisterReq req) {
        authService.register(req);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<AuthDto.LoginResp> login(@Valid @RequestBody AuthDto.LoginReq req) {
        return Result.ok(authService.login(req));
    }
}
