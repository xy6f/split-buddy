package com.splitbuddy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析工具。
 *
 * <p>选型说明（面试点）：JWT 是无状态登录态方案，服务端不存 session，
 * 天然适配水平扩容；代价是"注销难"——本项目用短有效期(12h) + 后续可加
 * Redis 黑名单的方式弥补，这里先注释记录取舍。</p>
 *
 * <p>claims 里只放最小信息：subject=userId、username。绝不放密码或敏感数据，
 * JWT 载荷只是 Base64 编码，不是加密，任何人可解。</p>
 */
@Component
public class JwtUtil {

    /** 签名密钥：从配置注入，HS256 要求至少 256bit（32 字节） */
    private final SecretKey key;
    /** 有效期分钟数 */
    private final long expireMinutes;

    public JwtUtil(@Value("${splitbuddy.jwt.secret}") String secret,
                   @Value("${splitbuddy.jwt.expire-minutes}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /**
     * 签发令牌。
     *
     * @param userId   用户ID（放 subject）
     * @param username 用户名（放自定义 claim，省一次查库）
     */
    public String generate(Long userId, String username) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expireMinutes * 60_000L);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析并校验令牌（签名 + 过期时间）。
     *
     * @return 解析出的载荷；任何校验失败抛 JwtException，由过滤器捕获成"未认证"
     */
    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)   // 非法签名/过期都在这里抛异常
                .getBody();
    }
}
