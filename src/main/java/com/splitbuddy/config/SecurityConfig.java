package com.splitbuddy.config;

import com.splitbuddy.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（对应计划书第 4 章横切层）。
 *
 * <p>关键决策（面试高频）：
 * 1. 无状态：SESSION 设为 STATELESS——JWT 场景不需要 JSESSIONID，
 *    同时天然规避 CSRF（CSRF 依赖 cookie 自动携带，Bearer 头不成立）；
 * 2. JwtAuthFilter 插在 UsernamePasswordAuthenticationFilter 之前：
 *    先"声明身份"，后"检查权限"，顺序反了过滤器链拿不到认证对象；
 * 3. 只放行 /api/auth/**，其余全部要认证——路由级粗粒度，
 *    细粒度（账本成员）交给业务注解 @LedgerMember（W2 实现）。</p>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** 密码编码器：BCrypt 自带盐、不可逆，强度 10 是合理默认 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
            // 前后端分离 + 无状态：关闭 CSRF，开启会误杀 POST 请求
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                // 注册/登录必须匿名可访问
                .requestMatchers("/api/auth/**").permitAll()
                // 演示静态页匿名可打开（它调的 API 仍要带令牌——页面公开≠接口公开）
                .requestMatchers("/", "/index.html", "/favicon.ico", "/css/**", "/js/**").permitAll()
                .anyRequest().authenticated())
            // 自定义 JWT 过滤器加入链
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // 全量测试 A6/A7 修复：未携带/伪造令牌期望 HTTP 401。
            // 根因：Boot 默认 SecurityFilterChain 未配置 authenticationEntryPoint，
            // 匿名访问受保护资源时走 Http403ForbiddenEntryPoint——返回 403 且空响应体。
            // 403 语义应是"已认证但无权限"（本项目的 403 由 Service 层成员断言给出），
            // "未认证"必须翻译为 401。这里显式装一个返回统一 Result JSON 的 401 入口，
            // code 与 CurrentUser 的 40100 口径保持一致。
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":40100,\"msg\":\"请先登录\",\"data\":null}");
            }));
        return http.build();
    }
}
