package com.splitbuddy.module.reminder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.splitbuddy.common.RedisSafeService;
import com.splitbuddy.module.balance.service.BalanceService;
import com.splitbuddy.module.expense.entity.Expense;
import com.splitbuddy.module.expense.mapper.ExpenseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 债务到期提醒定时任务（FR-08，计划书 8.2）。
 *
 * <p>解决什么问题：记一笔时可选填 dueDate（期望结清日）。到期后若账本里仍有人
 * 处于"应付"状态（净额小于 0，即未结清），需要催一次，而不是每小时重复轰炸。
 * 每小时整点全量扫描一次：单机 MVP 够用，量级（笔数）远小于扫描成本阈值；
 * 真要水平扩展，把"谁负责扫哪批"的分片或分布式锁（如 ShedLock）再补上即可，
 * 这里先用 Redis setnx 解决更实际的"重复提醒"问题。</p>
 *
 * <p>去重与降级（关键取舍，面试点）：</p>
 * <ul>
 *   <li>每条到期支出先过 Redis key remind:expense:{id} 的 setnx + 7 天 TTL——
 *       抢到才提醒，天然幂等；重启应用、cron 抖动、多实例都不会重复发；</li>
 *   <li>Redis 不可用时 RedisSafeService.tryAcquireMark 返回 true 降级为"仅日志、
 *       宁可重复不漏发"——提醒只是 log.info 输出（MVP 没有站内消息表），重复无害，
 *       丢提醒才是功能缺失；整个扫描再套一层 try/catch，任何异常绝不允许
 *       冒泡杀死调度线程池，更不允许影响应用启动（@Scheduled 只是 Bean 方法，
 *       Spring Data Redis 的懒连接特性保证 Redis 挂着重启后照常跑）。</li>
 * </ul>
 *
 * <p>"未结清"判定用 BalanceService.hasDebtor（净额表里有负数行）而不是查转账表：
 * 净额是结清状态的唯一权威物化视图（计划书 5.1），判据收敛到一处。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    /** 去重标记 TTL：7 天（计划书 8.2 约定）。到期支出最迟一周内会被处理或改期，够覆盖 */
    private static final Duration MARK_TTL = Duration.ofDays(7);

    /** Redis 去重键前缀：一笔到期支出一个键，语义直白便于 redis-cli 排查 */
    private static final String MARK_KEY_PREFIX = "remind:expense:";

    private final ExpenseMapper expenseMapper;
    private final BalanceService balanceService;
    private final RedisSafeService redisSafe;

    /**
     * 每小时整点扫描到期未结清的支出并提醒。
     * cron 表达式按"分 时 日 月 周"五段：0 点起步每小时一次。
     * fixedDelay 方案被否：扫描频率需求就是"每小时"这个语义，cron 更贴合且好改。
     *
     * <p>周期改为属性占位（默认值维持"每小时整点"原行为不变）：QA 验证 Redis 在线
     * 路径（setnx 去重键是否真实写入）时不必干等整点，可用启动参数
     * --splitbuddy.reminder.cron="0 * * * * *" 临时把周期压到每分钟，代码零改动。</p>
     */
    @Scheduled(cron = "${splitbuddy.reminder.cron:0 0 * * * *}")
    public void scanDueAndRemind() {
        try {
            doScan();
        } catch (Exception e) {
            // 兜底：DB 抖动等任何异常只记 error，下个小时自然重来；绝不让异常冒泡
            log.error("到期提醒扫描异常，本轮跳过", e);
        }
    }

    /** 单轮扫描：查询 -> 未结清判定 -> setnx 去重 -> 输出提醒 */
    private void doScan() {
        LocalDate today = LocalDate.now();
        // isNotNull 先过滤：绝大多数支出没填 dueDate，别把它们拉进结果集
        // deleted=0 由 MyBatis-Plus 逻辑删除配置自动追加，无需手写
        List<Expense> dueExpenses = expenseMapper.selectList(
                Wrappers.<Expense>lambdaQuery()
                        .isNotNull(Expense::getDueDate)
                        .le(Expense::getDueDate, today));
        if (dueExpenses.isEmpty()) {
            return;
        }
        int reminded = 0;
        for (Expense e : dueExpenses) {
            // 已结清（账本内无人处于应付状态）就不催——FR-08 语义是"到期未还"
            if (!balanceService.hasDebtor(e.getLedgerId())) {
                continue;
            }
            // setnx 去重：抢到标记才发；Redis 挂了返回 true，降级为宁可重复
            if (!redisSafe.tryAcquireMark(MARK_KEY_PREFIX + e.getId(), MARK_TTL)) {
                continue;
            }
            // MVP 的"站内消息"就是结构化日志（无消息表，见计划书 Could 级裁剪）；
            // 输出付款人/金额/到期日，方便直接转成通知渠道
            log.info("[到期提醒] 账本 {} 的支出 {}「{}」金额 {} 元应于 {} 结清，当前仍有成员欠款未清，请相关成员尽快结清",
                    e.getLedgerId(), e.getId(), e.getTitle(), e.getAmount(), e.getDueDate());
            reminded++;
        }
        if (reminded > 0) {
            log.info("到期提醒扫描完成：到期支出 {} 条，本轮实际提醒 {} 条", dueExpenses.size(), reminded);
        }
    }
}
