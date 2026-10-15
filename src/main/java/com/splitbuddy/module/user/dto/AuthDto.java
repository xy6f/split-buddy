package com.splitbuddy.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 认证相关 DTO 容器：请求/响应都是一次性数据结构，用 record 嵌套集中管理，
 * 避免小类文件爆炸（取舍说明：类多杂 vs 一文件多类，选前者规范但这里量小）。
 */
public final class AuthDto {

    private AuthDto() {}

    /** 注册请求：字段约束直接写注解，@Valid 触发，错误进 GlobalExceptionHandler */
    public record RegisterReq(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 6, max = 64) String password,
            @NotBlank @Size(max = 64) String nickname) {}

    /** 登录请求 */
    public record LoginReq(
            @NotBlank String username,
            @NotBlank String password) {}

    /** 登录响应：token + 基本信息，前端存 token 后续请求带头 */
    public record LoginResp(String token, Long userId, String nickname) {}
}
