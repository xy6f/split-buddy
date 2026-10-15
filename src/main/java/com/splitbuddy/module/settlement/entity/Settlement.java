package com.splitbuddy.module.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账/还钱记录（t_settlement，计划书 6.2）。
 *
 * <p>语义：from_user（原债务人）实际支付了 amount 给 to_user（原债权人），
 * 效果是净额 balance[from] += amount、balance[to] -= amount。
 * source 区分两种来源：EXEC_PLAN=一键执行算法方案（#12），
 * MANUAL=线下还钱手动登记（#13）——审计与对账时能分开统计。</p>
 */
@Data
@TableName("t_settlement")
public class Settlement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ledgerId;

    /** 付款方（原债务人） */
    private Long fromUser;

    /** 收款方（原债权人） */
    private Long toUser;

    private BigDecimal amount;

    /** EXEC_PLAN / MANUAL */
    private String source;

    private LocalDateTime settledAt;
}
