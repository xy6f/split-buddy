package com.splitbuddy.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * 全局异常处理：把异常统一翻译成 Result 响应体，Controller 里不写 try-catch。
 *
 * <p>三类分层处理（面试点：为什么参数错误和系统错误处理策略不同）：
 * 1. BizException —— 预期业务失败，按携带的 status 返回，日志 level 只到 warn；
 * 2. 校验失败 —— @Valid 不通过，取第一条字段错误返回 400；
 * 3. 兜底 Exception —— 未预期异常，日志 error 级带堆栈，但响应不回显内部信息（防泄露）。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> onBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> onInvalid(MethodArgumentNotValidException e) {
        // 只回显第一条字段错误，够用且响应体小
        FieldError first = e.getBindingResult().getFieldErrors().get(0);
        String msg = first.getField() + ": " + first.getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.fail(40000, msg));
    }

    /**
     * 请求体 JSON 语法错误 / 不可读：这是客户端输入问题，必须是 400。
     * 修复根因（全量测试 X1/X2 实测复现，也是历史"用户名注册 50000"的同类通道——
     * Git Bash 发中文 payload 被转成 GBK 字节后 JSON 解析直接抛
     * HttpMessageNotReadableException，此前无专属 handler，落入兜底 Exception
     * 分支变成 500/50000"未处理异常"，掩盖了"输入不合法"的真实语义）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> onBadBody(HttpMessageNotReadableException e) {
        log.warn("请求体 JSON 解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(40000, "请求体 JSON 解析失败（字段缺失/语法错误/编码非法）"));
    }

    /** 必填查询参数缺失（如 DELETE /expenses/{id} 不带 version）：客户端错误，400 而非 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> onMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数: {}", e.getParameterName());
        return ResponseEntity.badRequest().body(Result.fail(40000, "缺少必要参数: " + e.getParameterName()));
    }

    /** 参数类型不匹配（如 version=abc 无法转 Integer）：客户端错误，400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误 name={} value={}", e.getName(), e.getValue());
        return ResponseEntity.badRequest().body(Result.fail(40000, "参数类型错误: " + e.getName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> onUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.internalServerError()
                .body(Result.fail(50000, "服务器开小差了，请稍后再试"));
    }
}
