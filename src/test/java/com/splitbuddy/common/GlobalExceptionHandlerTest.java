package com.splitbuddy.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 全局异常处理器单测：把全量测试里修过的状态码语义全部钉死，防止回归——
 * <ul>
 *   <li>A2：重复注册 40901 必须映射 HTTP 409（曾误走单参构造返回 200）；</li>
 *   <li>A4/40101：登录失败必须 401；</li>
 *   <li>历史"用户名注册 50000"通道：非法 JSON/GBK 字节流必须 400 而非 500
 *       （曾落兜底分支变成 50000 未处理异常）；</li>
 *   <li>D3b：缺 version 参数必须 400；</li>
 *   <li>兜底：未知异常 500 + 50000，且不回显内部信息。</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void bizExceptionStatusPassthrough() {
        ResponseEntity<Result<Void>> dup = handler.onBiz(new BizException(40901, 409, "用户名已被占用"));
        assertEquals(409, dup.getStatusCode().value());
        assertEquals(40901, dup.getBody().getCode());

        ResponseEntity<Result<Void>> unauthorized = handler.onBiz(new BizException(40101, 401, "用户名或密码错误"));
        assertEquals(401, unauthorized.getStatusCode().value());

        ResponseEntity<Result<Void>> conflict = handler.onBiz(BizException.conflict("v"));
        assertEquals(409, conflict.getStatusCode().value());

        ResponseEntity<Result<Void>> forbidden = handler.onBiz(BizException.forbidden("v"));
        assertEquals(403, forbidden.getStatusCode().value());

        ResponseEntity<Result<Void>> notFound = handler.onBiz(BizException.notFound("账本"));
        assertEquals(404, notFound.getStatusCode().value());
    }

    @Test
    void badJsonBodyIsClientErrorNotServerError() {
        // GBK/畸形 payload 走这条通道：必须 400/40000，绝不能落兜底 500/50000
        ResponseEntity<Result<Void>> resp = handler.onBadBody(
                new HttpMessageNotReadableException("JSON parse error: Invalid UTF-8 start byte 0xb2"));
        assertEquals(400, resp.getStatusCode().value());
        assertEquals(40000, resp.getBody().getCode());
    }

    @Test
    void missingRequestParamIsClientError() {
        ResponseEntity<Result<Void>> resp = handler.onMissingParam(
                new MissingServletRequestParameterException("version", "Integer"));
        assertEquals(400, resp.getStatusCode().value());
        assertEquals(40000, resp.getBody().getCode());
    }

    @Test
    void unknownExceptionMapsTo500WithoutLeakingInternals() {
        ResponseEntity<Result<Void>> resp = handler.onUnknown(
                new IllegalStateException("secret sql fragment: insert into ..."));
        assertEquals(500, resp.getStatusCode().value());
        assertEquals(50000, resp.getBody().getCode());
        // 响应文案不携带原始异常信息（防内部实现泄露）
        assertFalse(resp.getBody().getMsg().contains("secret"));
    }
}
