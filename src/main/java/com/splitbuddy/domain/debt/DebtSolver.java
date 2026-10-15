package com.splitbuddy.domain.debt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 债务结清算法（计划书 5.2，Must FR-05 的硬核点）。
 *
 * <p>解决什么问题：给定账本内每个成员的净额（正=应收，负=应付，求和恒为 0），
 * 输出一组"谁转给谁多少钱"的转账方案，让所有人清零且转账次数尽量少。</p>
 *
 * <p>算法分两级：</p>
 * <ol>
 *   <li>朴素两两转账是 O(n²) 条边；贪心每轮"最大债权配最大债务"转 min 金额，
 *       每轮至少清零一人，转账数上界 k-1（k 为该零和团块人数）；</li>
 *   <li>零和子集优化：若净额能拆成互不相欠的团块（子集内求和为 0），各团块内部
 *       独立贪心，总转账数 = Σ(kᵢ-1) = n - 团块数，团块越多转账越少。
 *       找"最大团块划分"用状态压缩 DP：sum[mask] 预处理 O(2ⁿ)，
 *       f[mask] 只对所有和为 0 的 mask 枚举"包含最低位"的真子子集转移，
 *       n≤20 时 2ⁿ=1048576 内存与时间都可控（计划书性能目标 &lt;100ms）。</li>
 * </ol>
 *
 * <p>关键取舍：金额全程按"分"（long）计算，入口处一次校验标度，杜绝浮点误差；
 * 本类是纯静态函数、零 Spring 依赖，单测可直接覆盖（src/test 下有 ≥15 case）。
 * 极端对抗数据下子集 DP 仍可能退化到 O(3ⁿ) 量级，但真实记账金额几乎不会构造出
 * 海量零和子集，n&gt;20 时直接退化为单团块纯贪心，保证可用。</p>
 */
public final class DebtSolver {

    private DebtSolver() {
        // 纯函数工具类，禁止实例化
    }

    /** 状态压缩 DP 允许的最大非零净额人数：2^20 个 mask 是内存与耗时的平衡点 */
    private static final int MAX_DP_SIZE = 20;

    /**
     * 一条转账指令：fromUser（原债务人）转 toUser（原债权人）若干钱。
     *
     * @param fromUserId 付款方用户ID（净额为负的一方）
     * @param toUserId   收款方用户ID（净额为正的一方）
     * @param amount     转账金额（元，两位小数）
     */
    public record Transfer(Long fromUserId, Long toUserId, BigDecimal amount) {}

    /**
     * 求解结清方案。
     *
     * @param netByUser 净额表：userId -&gt; 净额（元）。正=应收，负=应付，0 可省略
     * @return 转账列表；净额全为 0 时返回空列表
     * @throws IllegalArgumentException 净额为 null、小数位超过 2 位，或求和非 0（违反对账不变量）
     */
    public static List<Transfer> solve(Map<Long, BigDecimal> netByUser) {
        if (netByUser == null || netByUser.isEmpty()) {
            return List.of();
        }

        // ---- 第一步：校验 + 转分 + 过滤零 ----
        List<Long> ids = new ArrayList<>();
        List<Long> cents = new ArrayList<>();
        long sum = 0;
        for (Map.Entry<Long, BigDecimal> e : netByUser.entrySet()) {
            Long id = e.getKey();
            BigDecimal net = e.getValue();
            if (id == null || net == null) {
                throw new IllegalArgumentException("净额表不允许 null 键或 null 值");
            }
            long c = toCents(net);
            sum += c;
            if (c != 0) {
                ids.add(id);
                cents.add(c);
            }
        }
        // 全账本净额求和恒为 0 是记账系统的对账不变量（计划书 5.1），破坏即数据异常，直接拒绝出方案
        if (sum != 0) {
            throw new IllegalArgumentException("净额之和必须为 0，当前为 " + sum + " 分");
        }

        int n = ids.size();
        if (n == 0) {
            return List.of();
        }

        long[] arr = new long[n];
        for (int i = 0; i < n; i++) {
            arr[i] = cents.get(i);
        }

        // ---- 第二步：零和子集划分（DP 找最多团块数）----
        List<int[]> groups = partitionZeroSumGroups(arr);

        // ---- 第三步：每个团块内部独立贪心配对 ----
        List<Transfer> out = new ArrayList<>();
        for (int[] group : groups) {
            greedyWithinGroup(group, ids, arr, out);
        }
        return out;
    }

    /**
     * 元转分：只接受最多两位小数，超出直接拒绝（DECIMAL(10,2) 精度的入口防呆）。
     */
    private static long toCents(BigDecimal yuan) {
        BigDecimal scaled = yuan.movePointRight(2);
        // 先判 scale 再取 longValueExact：1.234 元这类三位小数属于非法输入
        if (yuan.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("金额最多两位小数: " + yuan.toPlainString());
        }
        return scaled.longValue();
    }

    /**
     * 把下标集合划分成尽量多的零和子集（团块）。
     *
     * <p>返回每个团块的下标数组；n&gt;20 或全量只有一个零和子集时退化为单团块。</p>
     */
    private static List<int[]> partitionZeroSumGroups(long[] arr) {
        int n = arr.length;
        List<int[]> single = List.of(allIndices(n));
        // n>20 时 2^n 状态数爆炸，直接放弃子集优化，纯贪心（上界仍有保障）
        if (n > MAX_DP_SIZE) {
            return single;
        }

        int full = (1 << n) - 1;
        // sum[mask]：mask 内金额之和（分），lowbit 递推 O(2^n)
        long[] sum = new long[1 << n];
        for (int mask = 1; mask <= full; mask++) {
            int lb = mask & -mask;
            int bit = Integer.numberOfTrailingZeros(lb);
            sum[mask] = sum[mask ^ lb] + arr[bit];
        }

        // f[mask]：mask（要求和为 0）能划出的最多零和团块数；非零和 mask 为 -1
        int[] f = new int[1 << n];
        Arrays.fill(f, -1);
        f[0] = 0;
        for (int mask = 1; mask <= full; mask++) {
            if (sum[mask] != 0) {
                continue;
            }
            // 整个 mask 自成一个团块是最差情况
            int best = 1;
            int lb = mask & -mask;
            // 枚举包含最低位 lb 的真子集 sub：若存在更优划分，必有某个团块含 lb；
            // mask 和为 0 时 sub 和为 0 蕴含补集和也为 0，所以只需检查 sub
            for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                if ((sub & lb) == 0 || sum[sub] != 0) {
                    continue;
                }
                int rest = mask ^ sub;
                if (f[sub] >= 0 && f[rest] >= 0 && f[sub] + f[rest] > best) {
                    best = f[sub] + f[rest];
                }
            }
            f[mask] = best;
        }

        if (f[full] <= 1) {
            // 无可拆分的全局零和集：单团块
            return single;
        }

        // ---- 从 f 表回溯构造具体划分 ----
        List<int[]> groups = new ArrayList<>();
        int remaining = full;
        while (remaining != 0) {
            if (f[remaining] == 1) {
                groups.add(maskToIndices(remaining));
                break;
            }
            int lb = remaining & -remaining;
            boolean split = false;
            for (int sub = (remaining - 1) & remaining; sub > 0; sub = (sub - 1) & remaining) {
                if ((sub & lb) == 0 || sum[sub] != 0) {
                    continue;
                }
                int rest = remaining ^ sub;
                if (f[sub] >= 0 && f[rest] >= 0 && f[sub] + f[rest] == f[remaining]) {
                    groups.add(maskToIndices(sub));
                    remaining = rest;
                    split = true;
                    break;
                }
            }
            if (!split) {
                // 理论上不该发生（f 表一致性保证可回溯），兜底整块返回保证正确性优先
                groups.add(maskToIndices(remaining));
                break;
            }
        }
        return groups;
    }

    /**
     * 单个零和团块内的贪心配对：每轮最大债权 × 最大债务，转账 min 额，清零其一。
     *
     * <p>转账数上界 k-1：每条转账至少彻底清零一人，最后一条同时清两人。</p>
     */
    private static void greedyWithinGroup(int[] group, List<Long> ids, long[] arr, List<Transfer> out) {
        // 债权人/债务人各用"下标列表 + 余额列表"两个并行表维护（金额是分，必须 long）
        List<Integer> credIdx = new ArrayList<>();
        List<Long> credAmt = new ArrayList<>();
        List<Integer> debIdx = new ArrayList<>();
        List<Long> debAmt = new ArrayList<>();
        for (int idx : group) {
            if (arr[idx] > 0) {
                credIdx.add(idx);
                credAmt.add(arr[idx]);
            } else {
                debIdx.add(idx);
                debAmt.add(-arr[idx]);
            }
        }
        // 债权降序、债务降序（绝对值），保证"最大配最大"
        sortByAmountDesc(credIdx, credAmt);
        sortByAmountDesc(debIdx, debAmt);

        int ci = 0;
        int di = 0;
        while (ci < credIdx.size() && di < debIdx.size()) {
            long c = credAmt.get(ci);
            long d = debAmt.get(di);
            long pay = Math.min(c, d);
            out.add(new Transfer(ids.get(debIdx.get(di)), ids.get(credIdx.get(ci)), BigDecimal.valueOf(pay, 2)));
            // 就地扣减，谁先清零谁出列（转账数因此有 k-1 上界）
            if (c == pay) {
                ci++;
            } else {
                credAmt.set(ci, c - pay);
            }
            if (d == pay) {
                di++;
            } else {
                debAmt.set(di, d - pay);
            }
        }
        // 团块内求和为 0，双指针结束时两边必然同时耗尽，无需额外兜底
    }

    /** 按金额降序对 (下标, 金额) 两个并行列表做联动排序（数据量 <=20，插入排序即可） */
    private static void sortByAmountDesc(List<Integer> idx, List<Long> amt) {
        for (int i = 1; i < idx.size(); i++) {
            int k = i;
            while (k > 0 && amt.get(k) > amt.get(k - 1)) {
                idx.add(k, idx.remove(k - 1));
                amt.add(k, amt.remove(k - 1));
                k--;
            }
        }
    }

    /** 0..n-1 全下标数组 */
    private static int[] allIndices(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    /** mask 转下标数组 */
    private static int[] maskToIndices(int mask) {
        int[] a = new int[Integer.bitCount(mask)];
        int p = 0;
        for (int m = mask; m != 0; m &= m - 1) {
            a[p++] = Integer.numberOfTrailingZeros(m & -m);
        }
        return a;
    }
}
