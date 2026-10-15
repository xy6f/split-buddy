package com.splitbuddy.module.balance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 净额物化表实体（t_balance，计划书 5.1/6.2）。
 *
 * <p>为什么物化而不是每次从 expense+split 现算：净额表让 #10 查询和 #11 算法输入
 * 都是 O(成员数) 读取；代价是"删改一笔账"必须同步增量维护——所有写入都收敛到
 * BalanceService.applyDelta 一个方法，配 recalculate 全量重算兜底（计划书 10 风险）。
 * 不变量：同一 ledger 内 SUM(net) 恒为 0。</p>
 */
@Data
@TableName("t_balance")
public class Balance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ledgerId;

    private Long userId;

    /** 净额（元）：正=应收，负=应付 */
    private BigDecimal net;
}
