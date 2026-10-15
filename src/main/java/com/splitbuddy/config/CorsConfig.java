package com.splitbuddy.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 开发期 CORS 配置（前后端分离架构下，前端独立部署 / 本地直开页面时跨域访问 8081）。
 *
 * <p>为什么需要它：浏览器同源策略只拦"页面所在源 → 不同源的 XHR/fetch"。
 * 本项目前端产物在 {@code frontend/} 目录，与后端 8081 不同源（file:// 直开、
 * http://localhost:5001 静态服务器、或 Live Server 的 http://127.0.0.1:5500），
 * 后端不返回 CORS 响应头的话，所有 fetch 都会被浏览器拦截。</p>
 *
 * <p>关键决策（面试点）：</p>
 * <ul>
 *   <li><b>为什么用 FilterRegistrationBean&lt;CorsFilter&gt; 而不是 WebMvcConfigurer#addCorsMappings</b>：
 *       addCorsMappings 的 CORS 逻辑挂在 DispatcherServlet 的拦截器上，位置在
 *       Spring Security 过滤器链<b>之后</b>；而携带 Authorization: Bearer 的请求
 *       不是 CORS "简单请求"，浏览器会先发 OPTIONS 预检——预检不带令牌，
 *       若走到 Security 链会被 anyRequest().authenticated() 拦成 401，跨域直接失效。
 *       把 CorsFilter 注册为最高优先级（HIGHEST_PRECEDENCE，早于 Security 链的 -100），
 *       它直接应答 OPTIONS 并短路后续过滤器，<b>SecurityConfig 一行都不用改</b>，
 *       职责也干净：CORS 管"浏览器允不允许跨域读"，Security 管"要不要认证"。</li>
 *   <li>用 allowedOriginPatterns("*") 而不是 allowedOrigins("*")：前者可与
 *       allowCredentials(true) 共存（裸 "*" + 允许凭证会被 Spring 直接拒绝），
 *       并且响应时回显具体 Origin，能匹配 {@code Origin: null}——
 *       浏览器"双击打开 index.html"（file:// 协议）时发的正是 null Origin，
 *       这是 README 里的第一种启动方式。</li>
 *   <li>allowedHeaders 必须放行 Authorization：Bearer 令牌走请求头，
 *       这也是本项目天然免疫 CSRF、且预检不可省略的原因。</li>
 *   <li>maxAge=3600：预检结果浏览器缓存 1 小时，减少 OPTIONS 往返。</li>
 * </ul>
 *
 * <p><b>生产环境警告：本配置仅限开发。</b>生产应收敛 allowedOriginPatterns
 * 到具体前端域名（如 https://ledger.example.com），并关闭 allowCredentials
 * （Bearer 走头不走 Cookie，本就不需要凭证模式）；更好的上线形态是前端静态资源
 * 与后端 API 走同域网关（Nginx 同域反代，/ 指前端、/api 指后端 8081），
 * 同源之后根本不会触发 CORS——CORS 只是跨域开发阶段的"脚手架"，不是上线形态。</p>
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 开发期放开所有来源（含 file:// 直开页面时的 Origin: null）
        cfg.setAllowedOriginPatterns(java.util.List.of("*"));
        cfg.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Requested-With"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        // 必须抢在 Spring Security 过滤器链（order = -100）之前应答 OPTIONS 预检
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
