package com.splitbuddy.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统一响应体（计划书第 7 章约定：{code, msg, data}）。
 *
 * <p>约定 code=0 成功；非 0 为业务错误码，HTTP 状态另由 GlobalExceptionHandler 决定
 * （401/403/409 等语义用状态表达，code 只做业务细分）。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
@AllArgsConstructor
public class Result<T> {

    /** 0 表示成功，其余为业务错误码 */
    private int code;
    /** 人类可读信息，前端可直接展示 */
    private String msg;
    /** 业务数据，失败时可为 null */
    private T data;

    /** 成功且带数据 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    /** 成功且无返回值（写操作常用） */
    public static Result<Void> ok() {
        return new Result<>(0, "ok", null);
    }

    /** 业务失败：HTTP 仍返回 200，由 code 区分（参数类错误） */
    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
