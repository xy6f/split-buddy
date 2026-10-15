package com.splitbuddy.module.expense.service;

import com.splitbuddy.common.BizException;
import com.splitbuddy.module.expense.dto.ExpenseDto;
import com.splitbuddy.module.expense.entity.ExpenseSplit;
import com.splitbuddy.module.expense.entity.ShareType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分摊纯函数单测（全量测试 C 组用例的回归防线）。
 *
 * <p>重点锁死三件事：</p>
 * <ol>
 *   <li>EQUAL 奇数金额余数归付款人（C1 实测 33.34/33.33/33.33）；</li>
 *   <li>AMOUNT 合计≠总额必须 4xx（C2）；</li>
 *   <li>净额恒等式（C6）：任何合法分摊行求和必须恰好等于支出总额——
 *       付款人 +total 与各行 -share 相抵后净额增量为 0，
 *       这是"多轮随机操作后 SUM(balances)=0"在纯函数层的等价命题，
 *       用伪随机批量验证（固定种子可复现）。</li>
 * </ol>
 */
class ExpenseComputeSplitsTest {

    private static ExpenseDto.SplitReq req(long userId, String type, String share) {
        return new ExpenseDto.SplitReq(userId, type, new BigDecimal(share));
    }

    private static BigDecimal sum(List<ExpenseSplit> rows) {
        BigDecimal s = BigDecimal.ZERO;
        for (ExpenseSplit r : rows) {
            s = s.add(r.getAmount());
        }
        return s;
    }

    // ---------- C1 EQUAL 余数归付款人 ----------

    @Test
    void equalOddAmountRemainderGoesToPayerWhenPayerAmongParticipants() {
        List<ExpenseSplit> rows = ExpenseService.computeSplits(
                new BigDecimal("100.00"), 1L,
                List.of(req(1, "EQUAL", "0"), req(2, "EQUAL", "0"), req(3, "EQUAL", "0")),
                ShareType.EQUAL);
        assertEquals(3, rows.size());
        // 10000 分 / 3 = 3333 余 1：付款人(1号) 33.34，其余 33.33
        assertEquals(new BigDecimal("33.34"), rows.get(0).getAmount());
        assertEquals(new BigDecimal("33.33"), rows.get(1).getAmount());
        assertEquals(new BigDecimal("33.33"), rows.get(2).getAmount());
        assertEquals(new BigDecimal("100.00"), sum(rows));
    }

    @Test
    void equalPayerNotAmongParticipantsAddsExtraRowForRemainder() {
        // 0.10 元 3 人均摊：3 分/人 + 1 分余数 -> 给不在场的付款人补一行
        List<ExpenseSplit> rows = ExpenseService.computeSplits(
                new BigDecimal("0.10"), 9L,
                List.of(req(1, "EQUAL", "0"), req(2, "EQUAL", "0"), req(3, "EQUAL", "0")),
                ShareType.EQUAL);
        assertEquals(4, rows.size());
        assertEquals(9L, rows.get(3).getUserId());
        assertEquals(new BigDecimal("0.10"), sum(rows));
    }

    @Test
    void equalExactDivisionNoRemainder() {
        List<ExpenseSplit> rows = ExpenseService.computeSplits(
                new BigDecimal("99.99"), 1L,
                List.of(req(1, "EQUAL", "0"), req(2, "EQUAL", "0"), req(3, "EQUAL", "0")),
                ShareType.EQUAL);
        assertEquals(new BigDecimal("33.33"), rows.get(0).getAmount());
        assertEquals(new BigDecimal("99.99"), sum(rows));
    }

    // ---------- C2/C4 AMOUNT 校验 ----------

    @Test
    void amountTypeRejectsSumMismatch() {
        BizException e = assertThrows(BizException.class, () -> ExpenseService.computeSplits(
                new BigDecimal("100.00"), 1L,
                List.of(req(1, "AMOUNT", "60"), req(2, "AMOUNT", "39")),
                ShareType.AMOUNT));
        assertEquals(400, e.getStatus());
    }

    @Test
    void amountTypeAcceptsExactSum() {
        List<ExpenseSplit> rows = ExpenseService.computeSplits(
                new BigDecimal("0.10"), 1L,
                List.of(req(1, "AMOUNT", "0.03"), req(2, "AMOUNT", "0.07")),
                ShareType.AMOUNT);
        assertEquals(new BigDecimal("0.10"), sum(rows));
    }

    @Test
    void zeroOrNegativeTotalRejected() {
        assertThrows(BizException.class, () -> ExpenseService.computeSplits(
                BigDecimal.ZERO, 1L, List.of(req(1, "EQUAL", "0")), ShareType.EQUAL));
        assertThrows(BizException.class, () -> ExpenseService.computeSplits(
                new BigDecimal("-5"), 1L, List.of(req(1, "EQUAL", "0")), ShareType.EQUAL));
    }

    @Test
    void moreThanTwoDecimalAmountsRejected() {
        // 10.005：落库会撞 DECIMAL(10,2) 静默舍入 -> 必须入口拒绝
        assertThrows(BizException.class, () -> ExpenseService.computeSplits(
                new BigDecimal("10.005"), 1L, List.of(req(1, "EQUAL", "0")), ShareType.EQUAL));
        assertThrows(BizException.class, () -> ExpenseService.computeSplits(
                new BigDecimal("10.00"), 1L,
                List.of(req(1, "AMOUNT", "3.335"), req(2, "AMOUNT", "6.665")), ShareType.AMOUNT));
    }

    // ---------- RATIO 最大余数法 ----------

    @Test
    void ratioLargestRemainderSumsExactlyToTotal() {
        List<ExpenseSplit> rows = ExpenseService.computeSplits(
                new BigDecimal("10.00"), 1L,
                List.of(req(1, "RATIO", "1"), req(2, "RATIO", "1"), req(3, "RATIO", "1")),
                ShareType.RATIO);
        assertEquals(3, rows.size());
        assertEquals(new BigDecimal("10.00"), sum(rows));
        // 每行与理论值 3.3333 相差不超过 1 分
        for (ExpenseSplit r : rows) {
            assertTrue(r.getAmount().subtract(new BigDecimal("3.33")).abs()
                    .compareTo(new BigDecimal("0.01")) <= 0);
        }
    }

    @Test
    void ratioRejectsNonPositiveShare() {
        assertThrows(BizException.class, () -> ExpenseService.computeSplits(
                new BigDecimal("10.00"), 1L,
                List.of(req(1, "RATIO", "0"), req(2, "RATIO", "1")), ShareType.RATIO));
    }

    // ---------- 重复行合并（UK(expense_id,user_id) 前置保障） ----------

    @Test
    void duplicateUserRowsAreMerged() {
        List<ExpenseSplit> rows = ExpenseService.computeSplits(
                new BigDecimal("10.00"), 1L,
                List.of(req(1, "AMOUNT", "3"), req(1, "AMOUNT", "2"), req(2, "AMOUNT", "5")),
                ShareType.AMOUNT);
        assertEquals(2, rows.size());
        assertEquals(new BigDecimal("5.00"), rows.get(0).getAmount());
    }

    // ---------- C6 净额恒等式（纯函数层等价命题） ----------

    /**
     * 随机批量验证：对任意合法输入（EQUAL/RATIO/AMOUNT），
     * 分摊行求和恒等于支出总额。付款人 +total、各行 -share 的净额增量
     * 由此恒为 0——多轮随机记账后 SUM(balances)=0 的根基。
     * 固定种子 Random(42)，失败可复现。
     */
    @Test
    void netIdentityPropertySplitsAlwaysSumToTotal() {
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            long totalCents = 1 + rnd.nextInt(100000); // 0.01 ~ 1000.00
            BigDecimal total = BigDecimal.valueOf(totalCents, 2);
            int n = 1 + rnd.nextInt(6);
            long payer = rnd.nextInt(8);
            List<ExpenseDto.SplitReq> reqs = new ArrayList<>();
            List<String> types = List.of("EQUAL", "RATIO", "AMOUNT");
            String type = types.get(rnd.nextInt(3));
            long budget = totalCents;
            for (int i = 0; i < n; i++) {
                long uid = rnd.nextInt(8); // 允许重复：合并后仍需恒等
                if ("RATIO".equals(type)) {
                    reqs.add(req(uid, type, String.valueOf(1 + rnd.nextInt(10))));
                } else if ("AMOUNT".equals(type)) {
                    // 精确构造：最后一行吃掉剩余预算，保证 sum==total
                    long c = (i == n - 1) ? budget : rnd.nextInt((int) (budget + 1));
                    budget -= c;
                    reqs.add(req(uid, type, BigDecimal.valueOf(c, 2).toPlainString()));
                } else {
                    reqs.add(req(uid, type, "0"));
                }
            }
            List<ExpenseSplit> rows = ExpenseService.computeSplits(
                    total, payer, reqs, ShareType.valueOf(type));
            assertEquals(0, sum(rows).compareTo(total),
                    "第 " + round + " 轮恒等式破坏 type=" + type + " total=" + total);
        }
    }
}
