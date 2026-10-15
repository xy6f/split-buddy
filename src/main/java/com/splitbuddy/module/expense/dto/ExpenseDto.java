package com.splitbuddy.module.expense.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 支出相关 DTO 容器（接口 #6~#9，计划书 6.2 表结构对应的传输形态）。
 *
 * <p>请求与视图严格分离：请求 record 带 Bean Validation 注解在入口防呆，
 * 视图 record 只暴露该暴露的字段（如 deleted 永不出现在响应里）。</p>
 */
public final class ExpenseDto {

    private ExpenseDto() {}

    /**
     * 分摊行请求：share 字段按 shareType 语义复用——
     * EQUAL 忽略；RATIO 为权重（如 2 表示两份）；AMOUNT 为该人应承担金额（元）。
     */
    public record SplitReq(
            @NotNull Long userId,
            @NotBlank String shareType,
            @NotNull @DecimalMin("0.00") BigDecimal share) {}

    /** 记一笔（#6）：付款人/参与人必须是账本成员，Service 层校验 */
    public record CreateReq(
            @NotBlank @Size(max = 128) String title,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotNull Long paidBy,
            @NotEmpty List<@Valid SplitReq> splits,
            @NotNull LocalDate expenseDate,
            LocalDate dueDate) {}

    /** 改一笔（#7）：整体替换语义（title/金额/分摊全部给新值），version 必传做乐观锁 */
    public record UpdateReq(
            @NotBlank @Size(max = 128) String title,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotNull Long paidBy,
            @NotEmpty List<@Valid SplitReq> splits,
            @NotNull LocalDate expenseDate,
            LocalDate dueDate,
            @NotNull Integer version) {}

    /** 分摊行视图 */
    public record SplitVO(Long userId, String shareType, BigDecimal amount) {}

    /** 支出明细视图：version 一并返回，前端改/删时原样带回做乐观锁 */
    public record ExpenseVO(
            Long id, Long ledgerId, String title, BigDecimal amount, Long paidBy,
            LocalDate expenseDate, LocalDate dueDate, Integer version,
            List<SplitVO> splits) {}

    /**
     * 分页包装：不直接序列化 MyBatis-Plus 的 IPage——它的 JSON 带 orders/optimizeCountSql
     * 等内部字段，契约脏且版本升级可能破坏前端；自定义 record 才是稳定 API。
     */
    public record PageVO<T>(List<T> records, long total, long page, long size) {}
}
