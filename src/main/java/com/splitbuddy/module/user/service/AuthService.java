package com.splitbuddy.module.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.splitbuddy.common.BizException;
import com.splitbuddy.module.user.dto.AuthDto;
import com.splitbuddy.module.user.entity.User;
import com.splitbuddy.module.user.mapper.UserMapper;
import com.splitbuddy.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 注册/登录业务。
 *
 * <p>并发注册防重（面试点）：不先 SELECT 判存在再 INSERT（check-then-act 竞态），
 * 而是直接插、靠 uk_username 唯一键兜底、捕获 DuplicateKeyException——
 * "用数据库约束而不是用代码流程保证唯一性"。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** 注册：散列密码入库。BCrypt 每次加盐，同密码两次注册散列不同属正常 */
    public void register(AuthDto.RegisterReq req) {
        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setNickname(req.nickname());
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            // 修复：原为 BizException(40901,...) 走单参构造，HTTP status 默认 200，
            // 但 40901 语义是"用户名冲突"，应返回 HTTP 409。根因是构造器选择错误——
            // 用 (code, status, msg) 三参构造显式带上 409，与 GlobalExceptionHandler 的
            // status 透传逻辑一致（A2 用例：重复注册期望 409）。
            throw new BizException(40901, 409, "用户名已被占用");
        }
    }

    /**
     * 登录：查用户 -> 比对密码 -> 签发 JWT。
     *
     * <p>注意"用户不存在"与"密码错"返回同一提示：不泄露用户枚举信息，
     * 安全细节（面试常抓）。</p>
     */
    public AuthDto.LoginResp login(AuthDto.LoginReq req) {
        // 必须走 selectForLogin：通用查询因 @TableField(select=false) 拿不到散列
        //（冒烟实测教训：注册成功但永远登录不上，单测发现不了、端到端才暴露）
        User u = userMapper.selectForLogin(req.username());
        if (u == null || !passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            throw new BizException(40101, 401, "用户名或密码错误");
        }
        String token = jwtUtil.generate(u.getId(), u.getUsername());
        return new AuthDto.LoginResp(token, u.getId(), u.getNickname());
    }
}
