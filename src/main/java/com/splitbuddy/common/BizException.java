package com.splitbuddy.common;

import lombok.Getter;

/**
 * 业务异常：Service 层主动抛出，由 GlobalExceptionHandler 统一翻译成响应体。
 *
 * <p>设计点（面试可讲）：异常携带 HTTP 状态码语义，例如乐观锁冲突抛
 * status=409，权限不足抛 403，让 REST 语义完整而不是全部 200。</p>
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码，进 Result.code */
    private final int code;
    /** HTTP 状态码语义，进 ResponseEntity.status */
    private final int status;

    public BizException(int code, String message) {
        this(code, 200, message);
    }

    public BizException(int code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
        // 记账类业务不需要堆栈展开（异常即结果），关掉提升一点性能也表明取舍意识
        // no-op: keep stacktrace for debugging in dev
    }

    /** 常用工厂：资源不存在 */
    public static BizException notFound(String what) {
        return new BizException(40400, 404, what + "不存在");
    }

    /** 常用工厂：越权/非账本成员 */
    public static BizException forbidden(String msg) {
        return new BizException(40300, 403, msg);
    }

    /** 常用工厂：乐观锁冲突（计划书 5.3 的 409 语义） */
    public static BizException conflict(String msg) {
        return new BizException(40900, 409, msg);
    }
}
