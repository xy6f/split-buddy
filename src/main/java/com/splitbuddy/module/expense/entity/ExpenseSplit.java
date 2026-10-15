package com.splitbuddy.module.expense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 支出分摊明细（t_expense_split）：一笔支出"谁该出多少"的一行。
 *
 * <p>不变量：同一 expense 下 SUM(amount) 恒等于 expense.amount（计划书 6.2 校验），
 * 由 SplitCalculator 在写库前用"整数分"算账保证，不依赖数据库约束。</p>
 *
 * <p>删除策略与 t_expense 不同：改一笔时旧 split 物理删除重插——分摊行是从属数据，
 * 没有独立审计价值（审计在 t_audit_log），保留逻辑删只会让 UK(expense_id,user_id)
 * 在"同两人换金额重分"时撞键。</p>
 */
@Data
@TableName("t_expense_split")
public class ExpenseSplit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long expenseId;

    private Long userId;

    /** 分摊方式：EQUAL 均摊 / RATIO 按份额 / AMOUNT 指定金额（见 ShareType） */
    private String shareType;

    /** 该行承担的金额（元）：均摊场景下付款人行可能多吃余数（计划书 10 风险） */
    private BigDecimal amount;
}
