package com.splitbuddy.security;

import com.splitbuddy.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户读取工具。
 *
 * <p>为什么不直接在 Controller 方法参数注入？—— Spring 的
 * {@code @AuthenticationPrincipal} 也能做，这里提供静态入口是给 AOP 切面
 * （W2 审计日志）用：切面里拿不到方法参数上的 principal。</p>
 */
public final class CurrentUser {

    private CurrentUser() {
        // 工具类禁止实例化
    }

    /** 取当前用户；未登录直接抛 401 语义异常 */
    public static LoginUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser user)) {
            throw new BizException(40100, 401, "请先登录");
        }
        return user;
    }

    public static Long userId() {
        return require().userId();
    }
}
