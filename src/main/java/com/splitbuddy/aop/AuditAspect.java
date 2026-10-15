package com.splitbuddy.aop;

import com.splitbuddy.module.audit.entity.AuditLog;
import com.splitbuddy.module.audit.mapper.AuditLogMapper;
import com.splitbuddy.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Parameter;

/**
 * 审计切面（计划书 4 章 aop/AuditAspect）：@Audited 方法成功返回后写 t_audit_log。
 *
 * <p>解决什么问题：把"记录谁改了什么"从每个业务方法里抽出来，业务代码零侵入；
 * 审计写失败绝不阻断主业务（整体 try/catch，只降级为 error 日志）——
 * "审计是旁路，不是主路"。</p>
 *
 * <p>实现要点（对应面试清单 11.3）：</p>
 * <ul>
 *   <li>@AfterReturning 而非 @Around：只在成功后落账，失败事务已回滚无需审计；</li>
 *   <li>操作人从 SecurityContext 取（CurrentUser），而不是方法参数——身份来自凭证；</li>
 *   <li>SpEL 求值用 SimpleEvaluationContext：只读属性访问，禁类型引用/构造器，
 *       防表达式注入（标准 EvaluationContext 能执行任意代码，这里刻意不用）；</li>
 *   <li>Boot 默认 CGLIB 代理类切面，@Audited 标在类上无需接口。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** detail 列宽 2048，超长截断（截断标记保留，方便识别丢数据） */
    private static final int DETAIL_MAX = 2000;

    private final AuditLogMapper auditLogMapper;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @AfterReturning(pointcut = "@annotation(audited)", returning = "result", argNames = "jp,result,audited")
    public void audit(JoinPoint jp, Object result, Audited audited) {
        try {
            AuditLog logRow = new AuditLog();
            // 身份：定时任务等无登录上下文的调用拿不到用户，记 0 而不是让审计抛错
            try {
                logRow.setUserId(CurrentUser.userId());
            } catch (Exception e) {
                logRow.setUserId(0L);
            }
            logRow.setAction(audited.action());
            logRow.setTargetType(audited.targetType());
            logRow.setTargetId(resolveTargetId(jp, result, audited));
            logRow.setDetail(buildDetail(jp));
            auditLogMapper.insert(logRow);
        } catch (Exception e) {
            // 审计失败只降级日志：不能因为旁路把主业务事务带崩
            log.error("审计写入失败 action={} target={}", audited.action(), audited.targetType(), e);
        }
    }

    /** SpEL 取目标ID：上下文注入方法参数（按参数名）+ #result；失败记 0 */
    private Long resolveTargetId(JoinPoint jp, Object result, Audited audited) {
        if (audited.targetIdExpr().isEmpty()) {
            return 0L;
        }
        try {
            // SimpleEvaluationContext：允许属性/方法读取但禁止 T() 类型引用，注入面收敛
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withInstanceMethods().build();
            Parameter[] params = ((org.aspectj.lang.reflect.MethodSignature) jp.getSignature())
                    .getMethod().getParameters();
            Object[] args = jp.getArgs();
            String[] names = nameDiscoverer.getParameterNames(
                    ((org.aspectj.lang.reflect.MethodSignature) jp.getSignature()).getMethod());
            for (int i = 0; i < args.length; i++) {
                String name = names != null && names.length > i ? names[i] : ("p" + i);
                ctx.setVariable(name, args[i]);
                // #p0/#a0 别名：编译未保留参数名时的兜底写法
                ctx.setVariable("p" + i, args[i]);
            }
            ctx.setVariable("result", result);
            Object v = parser.parseExpression(audited.targetIdExpr()).getValue(ctx);
            if (v instanceof Number num) {
                return num.longValue();
            }
            return 0L;
        } catch (Exception e) {
            log.warn("审计 targetId 表达式求值失败 expr={}", audited.targetIdExpr(), e);
            return 0L;
        }
    }

    /** detail = JSON {method, args 摘要}；对象入参 toString（record 自带可读格式），截断保护 */
    private String buildDetail(JoinPoint jp) {
        StringBuilder sb = new StringBuilder();
        sb.append(jp.getSignature().getName()).append('(');
        Object[] args = jp.getArgs();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            // DTO 全部是 record：toString 天然可读；实体/集合过大时靠截断兜底
            sb.append(args[i] == null ? "null" : String.valueOf(args[i]));
            if (sb.length() > DETAIL_MAX) {
                break;
            }
        }
        sb.append(')');
        String detail = sb.toString();
        return detail.length() > DETAIL_MAX
                ? detail.substring(0, DETAIL_MAX) + "...(truncated)"
                : detail;
    }
}
