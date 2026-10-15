package com.splitbuddy.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：Spring Security 过滤器链中替代默认 session 认证的一环。
 *
 * <p>职责单一：从 Authorization 头取 Bearer 令牌 -> 解析 -> 组装已认证对象放进
 * SecurityContext。解析失败不抛错、直接放行给后续链（表现为"匿名"），
 * 由 AuthorizationFilter 决定是否拒绝——这是"认证失败 != 未授权"的正确姿势。</p>
 *
 * <p>principal 放自定义 {@link LoginUser} 而不是裸字符串，下游 CurrentUser 才能
 * 直接拿到 userId 用于"账本成员校验"等业务。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                LoginUser user = new LoginUser(
                        Long.valueOf(claims.getSubject()),
                        claims.get("username", String.class));
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // 令牌非法/过期：保持匿名状态继续过滤链（不打印整个令牌，日志安全）
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
    // LoginUser 已迁至 service 模块同包（分层修正：principal 模型属于领域，不属于过滤器）
}
