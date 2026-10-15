package com.splitbuddy.module.expense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 支出实体（t_expense，计划书 6.2）：一笔共同消费的事实记录。
 *
 * <p>两个并发/生命周期设计点：</p>
 * <ul>
 *   <li>@Version 乐观锁（计划书 5.3）：MyBatis-Plus 的 OptimisticLockerInnerInterceptor
 *       会在 updateById 的 WHERE 里自动追加 version 条件并替换新值，
 *       影响行数 0 = 有人先改了这笔账，Service 层据此抛 409；</li>
 *   <li>deleted 逻辑删除：字段名命中 application.yml 的全局
 *       logic-delete-field 配置，MP 自动把 select 改写为 deleted=0、
 *       deleteById 改写为 UPDATE deleted=1，历史审计可追溯。</li>
 * </ul>
 */
@Data
@TableName("t_expense")
public class Expense {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属账本：idx_ledger_date 组合索引前缀，列表查询主路径 */
    private Long ledgerId;

    private String title;

    /** 支出总额（元，两位小数）：数据库 DECIMAL(10,2)，Java 侧必须 BigDecimal，禁 double */
    private BigDecimal amount;

    /** 付款人 user_id：垫付方，净额 = +amount 再减去自己承担的分摊 */
    private Long paidBy;

    private LocalDate expenseDate;

    /** 期望结清日：NULL 表示不催；W4 到期提醒扫描此列 */
    private LocalDate dueDate;

    /** 乐观锁版本号。insert 前显式置 0：MP 插入不回写该字段，null 会导致首次 update 失败 */
    @Version
    private Integer version;

    /** 逻辑删除标记 0/1，命中全局配置，不需要 @TableLogic */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
