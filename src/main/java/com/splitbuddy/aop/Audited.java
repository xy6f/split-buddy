package com.splitbuddy.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（FR-09，计划书 4 章横切层）：标注在 Service 写方法上，
 * AuditAspect 在方法成功返回后写一条 t_audit_log。
 *
 * <p>关键取舍：</p>
 * <ul>
 *   <li>只审计成功操作（@AfterReturning）：失败的事务已回滚，记"未发生的变更"
 *       反而污染审计；失败原因在业务异常日志里查；</li>
 *   <li>targetIdExpr 用 SpEL 从参数/返回值取目标ID（#result 可用），
 *       避免切面靠"第一个 Long 参数"这种脆弱约定；</li>
 *   <li>RUNTIME 保留 + METHOD 目标：只允许方法级注解，语义清晰。</li>
 * </ul>
 *
 * <p>注意：注解必须打在 Service 实现方法上且经代理调用才生效——
 * 同类内部 this 调用不走代理，@Transactional 失效同理（面试点 11.1）。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 动作码，如 EXPENSE_CREATE */
    String action();

    /** 目标资源类型，如 EXPENSE */
    String targetType();

    /**
     * 目标ID的 SpEL 表达式，上下文变量：方法参数名（如 #expenseId）+ 返回值 #result。
     * 表达式求值失败或结果非数字时 targetId 记 0，不影响主流程。
     */
    String targetIdExpr() default "";
}
