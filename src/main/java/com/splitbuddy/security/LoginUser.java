package com.splitbuddy.security;

/**
 * 登录用户模型（SecurityContext 的 principal）。
 *
 * <p>分层修正记录：它原本是 web 层 JwtAuthFilter 的嵌套 record，
 * 但 service 层的 CurrentUser 也要读它——"下层依赖上层"违反分层。
 * 迁到 service/security 后：过滤器（web）负责"造"它，CurrentUser（service）负责"读"它，
 * 依赖方向重新变为 web -> service。</p>
 *
 * @param userId   用户ID
 * @param username 用户名
 */
public record LoginUser(Long userId, String username) {
}
