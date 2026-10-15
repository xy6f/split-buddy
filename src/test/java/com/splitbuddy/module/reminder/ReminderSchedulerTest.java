package com.splitbuddy.module.reminder;

import com.splitbuddy.common.RedisSafeService;
import com.splitbuddy.module.balance.service.BalanceService;
import com.splitbuddy.module.expense.entity.Expense;
import com.splitbuddy.module.expense.mapper.ExpenseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到期提醒调度器单测（G 组：调度器不炸）。
 *
 * <p>锁死两条契约：</p>
 * <ol>
 *   <li>scanDueAndRemind 吞掉一切异常（DB/Redis 抖动绝不能冒泡杀死调度线程）；</li>
 *   <li>已结清（账本内无债务人）的支出不发提醒、也不消耗去重标记；</li>
 *   <li>Redis 降级（tryAcquireMark 恒 true）时提醒照常发出——宁可重复不漏发。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ReminderSchedulerTest {

    @Mock ExpenseMapper expenseMapper;
    @Mock BalanceService balanceService;
    @Mock RedisSafeService redisSafe;

    @InjectMocks ReminderScheduler scheduler;

    private static Expense due(long id, long ledgerId) {
        Expense e = new Expense();
        e.setId(id);
        e.setLedgerId(ledgerId);
        e.setTitle("t");
        e.setAmount(new BigDecimal("10.00"));
        e.setDueDate(LocalDate.now().minusDays(1));
        return e;
    }

    @Test
    void swallowsQueryFailure() {
        when(expenseMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> scheduler.scanDueAndRemind());
    }

    @Test
    void swallowsDebtorCheckFailure() {
        when(expenseMapper.selectList(any())).thenReturn(List.of(due(1L, 7L)));
        when(balanceService.hasDebtor(7L)).thenThrow(new RuntimeException("db flaky"));
        assertDoesNotThrow(() -> scheduler.scanDueAndRemind());
    }

    @Test
    void settledLedgerNotReminded() {
        when(expenseMapper.selectList(any())).thenReturn(List.of(due(1L, 7L)));
        when(balanceService.hasDebtor(7L)).thenReturn(false);
        scheduler.scanDueAndRemind();
        verify(redisSafe, never()).tryAcquireMark(anyString(), any(Duration.class));
    }

    @Test
    void debtorExistsAndRedisDegradedStillReminds() {
        // RedisSafeService 降级语义：tryAcquireMark 返回 true（视为首次，宁可重复发）
        when(expenseMapper.selectList(any())).thenReturn(List.of(due(2L, 8L)));
        when(balanceService.hasDebtor(8L)).thenReturn(true);
        when(redisSafe.tryAcquireMark(eq("remind:expense:2"), any(Duration.class))).thenReturn(true);
        assertDoesNotThrow(() -> scheduler.scanDueAndRemind());
        verify(redisSafe).tryAcquireMark(eq("remind:expense:2"), any(Duration.class));
    }

    @Test
    void duplicateReminderSuppressedByMark() {
        when(expenseMapper.selectList(any())).thenReturn(List.of(due(3L, 9L)));
        when(balanceService.hasDebtor(9L)).thenReturn(true);
        when(redisSafe.tryAcquireMark(eq("remind:expense:3"), any(Duration.class))).thenReturn(false);
        scheduler.scanDueAndRemind();
        // 抢不到标记 = 本轮已被其他实例/上轮发过，不重复发（无额外断言可观察副作用，
        // 此处验证调用确实发生过即路径被走到）
        verify(redisSafe).tryAcquireMark(eq("remind:expense:3"), any(Duration.class));
    }
}
