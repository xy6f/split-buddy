package com.splitbuddy.domain.debt;

import com.splitbuddy.domain.debt.DebtSolver.Transfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结清算法单测（计划书 W3 交付：算法单测 >=15 case）。
 *
 * <p>纯函数、无 Spring 上下文，mvn test 只跑这类用例，不需要数据库。
 * 公共校验器 assertSolutionValid：把方案"模拟执行"一遍，
 * 断言每个用户净额恰好清零——比只断言转账数更能兜住算法正确性。</p>
 */
class DebtSolverTest {

    /** 快捷构造净额表：varargs 的 (userId, 净额元) 对 */
    private static Map<Long, BigDecimal> nets(Object... kv) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(((Number) kv[i]).longValue(), new BigDecimal(kv[i + 1].toString()));
        }
        return m;
    }

    /**
     * 方案有效性不变量：
     * 1. 模拟执行所有转账后，每个用户净额必须恰好为 0（不多转、不少转、方向不反）；
     * 2. 每条转账金额必须为正。
     */
    private static void assertSolutionValid(Map<Long, BigDecimal> input, List<Transfer> plan) {
        Map<Long, BigDecimal> work = new HashMap<>(input);
        for (Transfer t : plan) {
            assertTrue(t.amount().signum() > 0, "转账金额必须为正: " + t);
            assertTrue(t.fromUserId() != null && t.toUserId() != null, "转账双方不能为 null");
            assertTrue(!t.fromUserId().equals(t.toUserId()), "不能给自己转账: " + t);
            // 债务人付款后净额 +pay（应付减少），债权人收款后净额 -pay（应收减少）
            work.merge(t.fromUserId(), t.amount(), BigDecimal::add);
            work.merge(t.toUserId(), t.amount().negate(), BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> e : work.entrySet()) {
            assertEquals(0, e.getValue().signum(), "用户 " + e.getKey() + " 执行方案后净额未清零: " + e.getValue());
        }
    }

    private static void run(Map<Long, BigDecimal> input, int expectedTransfers) {
        List<Transfer> plan = DebtSolver.solve(input);
        assertSolutionValid(input, plan);
        assertEquals(expectedTransfers, plan.size(), "转账次数不符预期，方案=" + plan);
    }

    // ---------- 基础/边界 ----------

    @Test
    @DisplayName("case1 空表：无账可结")
    void emptyMap() {
        assertTrue(DebtSolver.solve(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("case2 全零净额：返回空方案")
    void allZero() {
        run(nets(1, "0", 2, "0", 3, "0.00"), 0);
    }

    @Test
    @DisplayName("case3 零值条目混入：应被过滤，不影响方案")
    void zeroEntriesFiltered() {
        run(nets(1, "10", 2, "-10", 3, "0"), 1);
    }

    @Test
    @DisplayName("case4 单债权单债务：一条转账")
    void singlePair() {
        List<Transfer> plan = DebtSolver.solve(nets(1, "25.00", 2, "-25.00"));
        assertSolutionValid(nets(1, "25.00", 2, "-25.00"), plan);
        assertEquals(1, plan.size());
        assertEquals(2L, plan.get(0).fromUserId());
        assertEquals(1L, plan.get(0).toUserId());
        assertEquals(0, new BigDecimal("25.00").compareTo(plan.get(0).amount()));
    }

    @Test
    @DisplayName("case5 净额求和非零：违反对账不变量，拒绝出方案")
    void nonZeroSumRejected() {
        assertThrows(IllegalArgumentException.class, () -> DebtSolver.solve(nets(1, "10", 2, "-3")));
    }

    @Test
    @DisplayName("case6 三位小数金额：入口校验拒绝（DECIMAL(10,2) 防呆）")
    void overScaleRejected() {
        assertThrows(IllegalArgumentException.class, () -> DebtSolver.solve(nets(1, "1.234", 2, "-1.234")));
    }

    // ---------- 金额精度边界 ----------

    @Test
    @DisplayName("case7 最小金额 0.01：一分也能正确结清")
    void oneCentBoundary() {
        run(nets(1, "0.01", 2, "-0.01"), 1);
        run(nets(1, "0.01", 2, "0.01", 3, "-0.02"), 2);
    }

    @Test
    @DisplayName("case8 大额 99999999.99：分转 long 不溢出、金额不回环")
    void largeAmountBoundary() {
        run(nets(1, "99999999.99", 2, "-49999999.99", 3, "-50000000.00"), 2);
    }

    @Test
    @DisplayName("case9 除不尽金额（100 元三人分）：100/3 的 1 分余额也走通")
    void indivisibleAmount() {
        // 模拟均摊余数归付款人后的净额：付款人 -33.34，其余两人各 -33.33，付款人应收 100
        run(nets(1, "66.66", 2, "-33.33", 3, "-33.33"), 2);
    }

    // ---------- 结构与优化 ----------

    @Test
    @DisplayName("case10 三人循环债：无零和子集可拆，贪心 2 条清零")
    void cycleThree() {
        // A 欠 B、B 欠 C、C 欠 A 的净额化表示：+5/+5/-10 类结构，转账数 n-1=2
        run(nets(1, "5", 2, "-10", 3, "5"), 2);
    }

    @Test
    @DisplayName("case11 一债权多债务：恰为债务人数")
    void oneCreditorManyDebtors() {
        run(nets(1, "60", 2, "-10", 3, "-20", 4, "-30"), 3);
    }

    @Test
    @DisplayName("case12 一债务多债权：恰为债权人数")
    void oneDebtorManyCreditors() {
        run(nets(1, "-60", 2, "10", 3, "20", 4, "30"), 3);
    }

    @Test
    @DisplayName("case13 两个互不相欠团块：贪心不拆分需 5 条，子集 DP 优化后仅 3 条")
    void subsetSplitBeatsPureGreedy() {
        // 团块A：1:+10 2:-6 3:-4；团块B：4:+7 5:-7。跨团块乱配会多转，DP 必须找到 2 团块
        run(nets(1, "10", 2, "-6", 3, "-4", 4, "7", 5, "-7"), 3);
    }

    @Test
    @DisplayName("case14 四人对对转账：DP 拆出 2 个二人团块，共 2 条")
    void twoIsolatedPairs() {
        run(nets(1, "10", 2, "-10", 3, "20", 4, "-20"), 2);
    }

    @Test
    @DisplayName("case15 嵌套零和子集：{1,2} 与 {3,4,5} 两团块，共 1+2=3 条")
    void nestedGroupSizes() {
        run(nets(1, "8", 2, "-8", 3, "15", 4, "-5", 5, "-10"), 3);
    }

    // ---------- 规模与不变量 ----------

    @Test
    @DisplayName("case16 n=20 随机净额：正确 + 计划书的 100ms 性能目标（放宽到 500ms 防 CI 抖动）")
    void twentyUsersPerformance() {
        java.util.Random rnd = new java.util.Random(42); // 固定种子保证可复现
        Map<Long, BigDecimal> m = new HashMap<>();
        long sum = 0;
        for (int i = 1; i < 20; i++) {
            long cents = 1 + rnd.nextInt(100_000); // 0.01 ~ 1000.00
            long sign = rnd.nextBoolean() ? 1 : -1;
            m.put((long) i, BigDecimal.valueOf(sign * cents, 2));
            sum += sign * cents;
        }
        // 第 20 人兜住总和为 0（对账不变量）
        m.put(20L, BigDecimal.valueOf(-sum, 2));
        long t0 = System.nanoTime();
        List<Transfer> plan = DebtSolver.solve(m);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertSolutionValid(m, plan);
        // 贪心上界：总转账数 <= n-1 恒成立（即使 DP 完全拆不出团块）
        assertTrue(plan.size() <= 19, "转账数超上界: " + plan.size());
        assertTrue(ms < 500, "n=20 求解耗时 " + ms + "ms 超出预算");
    }

    @Test
    @DisplayName("case17 转账次数上界性质：随机 12 人方案数不超过 n-1 且方案有效")
    void transferUpperBoundProperty() {
        java.util.Random rnd = new java.util.Random(7);
        for (int trial = 0; trial < 30; trial++) {
            Map<Long, BigDecimal> m = new HashMap<>();
            long sum = 0;
            for (int i = 1; i <= 11; i++) {
                long cents = rnd.nextInt(50_000) - 25_000; // 允许出现 0，验证过滤
                m.put((long) i, BigDecimal.valueOf(cents, 2));
                sum += cents;
            }
            m.put(12L, BigDecimal.valueOf(-sum, 2));
            List<Transfer> plan = DebtSolver.solve(m);
            assertSolutionValid(m, plan);
            assertTrue(plan.size() <= 11, "转账数超上界: " + plan.size());
        }
    }

    @Test
    @DisplayName("case18 完全相同金额的多人：贪心配对不产生零额转账")
    void equalAmountCrowd() {
        // 1 人垫付 40，5 人各摊 8：应收 40，五个 -8
        Map<Long, BigDecimal> m = nets(1, "40", 2, "-8", 3, "-8", 4, "-8", 5, "-8", 6, "-8");
        List<Transfer> plan = DebtSolver.solve(m);
        assertSolutionValid(m, plan);
        assertEquals(5, plan.size());
        for (Transfer t : plan) {
            assertEquals(0, new BigDecimal("8").compareTo(t.amount()), "不应出现合并或拆分转账: " + t);
        }
    }
}
