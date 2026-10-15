package com.splitbuddy.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体（t_audit_log，FR-09）："谁在何时改了哪笔账"。
 *
 * <p>写入方唯一：aop/AuditAspect 拦截 @Audited 注解方法后异步…（同步）插入。
 * 业务代码不手动写这张表，保证口径统一。detail 存 JSON 摘要（截断到列宽 2048），
 * 只存定位问题所需的入参，不存整行快照（取舍：存储成本 vs 全量审计，MVP 取摘要）。</p>
 */
@Data
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 user_id，取自 JWT 解析出的当前登录用户 */
    private Long userId;

    /** 动作码，如 EXPENSE_CREATE / SETTLEMENT_EXECUTE */
    private String action;

    /** 目标资源类型，如 EXPENSE / SETTLEMENT */
    private String targetType;

    /** 目标资源ID：由 @Audited 的 SpEL 表达式从入参或返回值取 */
    private Long targetId;

    /** JSON 摘要：方法名 + 入参toString，超长截断 */
    private String detail;

    private LocalDateTime createdAt;
}
