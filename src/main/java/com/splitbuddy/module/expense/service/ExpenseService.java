package com.splitbuddy.module.expense.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.splitbuddy.aop.Audited;
import com.splitbuddy.common.BizException;
import com.splitbuddy.module.balance.service.BalanceService;
import com.splitbuddy.module.expense.dto.ExpenseDto;
import com.splitbuddy.module.expense.entity.Expense;
import com.splitbuddy.module.expense.entity.ExpenseSplit;
import com.splitbuddy.module.expense.entity.ShareType;
import com.splitbuddy.module.expense.mapper.ExpenseMapper;
import com.splitbuddy.module.expense.mapper.ExpenseSplitMapper;
import com.splitbuddy.module.ledger.entity.Ledger;
import com.splitbuddy.module.ledger.mapper.LedgerMapper;
import com.splitbuddy.module.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 支出业务（接口 #6~#9，FR-03/06 + 计划书 8.1/5.3 两条主流程）。
 *
 * <p>解决什么问题：一笔账 = expense + n 条 split + n+1 条 balance 增量，
 * 任何一步失败都必须整体回滚——所以三个写方法都是 @Transactional 单事务边界，
 * 事务内顺序固定为"校验(不碰库) -&gt; 乐观锁/主表写入 -&gt; 从表 -&gt; 净额 -&gt; 清缓存"，
 * 让最容易失败的版本冲突最先暴露，减少无用功。</p>
 *
 * <p>关键取舍：</p>
 * <ul>
 *   <li>金额一律先转"整数分"再算（toCents），余数规则显式化：均摊除不尽的余数归付款人
 *       （计划书 10 风险），杜绝 33.33/33.33/33.34 之外的不确定分配；</li>
 *   <li>改一笔采用"整体替换"语义（删旧 split 重插 + 净额先回滚再应用），而不是逐行 diff——
 *       低频操作 O(n) 重算可接受，代码路径短、正确性容易论证（面试点 11.3 事务混合删插）；</li>
 *   <li>权限校验紧跟成员校验：改/删仅付款人本人或账本 owner 可做（计划书 7 章权限规则）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseMapper expenseMapper;
    private final ExpenseSplitMapper splitMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerService ledgerService;
    private final BalanceService balanceService;

    // ==================== #6 记一笔 ====================

    /**
     * 记一笔（8.1 事务序列）：校验 -&gt; insert expense -&gt; insert splits
     * -&gt; 净额增量（付款人+总额、各分摊行-金额）-&gt; 清相关人账本列表缓存。
     *
     * @return 新支出视图（含 id，前端后续改/删要带 version）
     */
    @Transactional
    @Audited(action = "EXPENSE_CREATE", targetType = "EXPENSE", targetIdExpr = "#result.id()")
    public ExpenseDto.ExpenseVO create(Long ledgerId, Long operatorId, ExpenseDto.CreateReq req) {
        // 成员断言内部已含"账本存在"校验（404/403 语义），无需再查 ledger 主表
        ledgerService.assertMember(ledgerId, operatorId);

        // 分摊计算：纯内存先算完，任何非法输入都不允许留下半成品数据
        ShareType type = uniformShareType(req.splits());
        List<ExpenseSplit> splits = computeSplits(req.amount(), req.paidBy(), req.splits(), type);
        assertAllMembers(ledgerId, req.paidBy(), splits);

        Expense e = new Expense();
        e.setLedgerId(ledgerId);
        e.setTitle(req.title());
        e.setAmount(req.amount().setScale(2, RoundingMode.UNNECESSARY));
        e.setPaidBy(req.paidBy());
        e.setExpenseDate(req.expenseDate());
        e.setDueDate(req.dueDate());
        // version/deleted 显式初始化：MP insert 不会回写数据库默认值，留 null 后续 update 的
        // WHERE version=? 会绑 null 永远失配——乐观锁闭环从插入就成立
        e.setVersion(0);
        e.setDeleted(0);
        expenseMapper.insert(e);

        for (ExpenseSplit s : splits) {
            s.setExpenseId(e.getId());
            splitMapper.insert(s);
        }

        // 净额增量（5.1）：付款人 +e.amount，各分摊人 -share。付款人同时是参与人时自然抵消
        applyBalances(ledgerId, e.getPaidBy(), e.getAmount(), splits);
        evictCaches(splits, e.getPaidBy());

        log.debug("记一笔成功 ledger={} expense={} amount={} splits={}",
                ledgerId, e.getId(), e.getAmount(), splits.size());
        return toVO(e, splits);
    }

    // ==================== #7 改一笔 ====================

    /**
     * 改一笔（5.3 乐观锁 + 混合删插回滚保证）：
     * 先 UPDATE 主表（带 version，影响行数 0 即 409 快速失败），
     * 再回滚旧 split 净额 -&gt; 物理删旧 split -&gt; 插新 split -&gt; 应用新净额。
     * 全部同事务：中途任何异常（含冲突）整体回滚，净额绝不半途漂移。
     */
    @Transactional
    @Audited(action = "EXPENSE_UPDATE", targetType = "EXPENSE", targetIdExpr = "#expenseId")
    public void update(Long expenseId, Long operatorId, ExpenseDto.UpdateReq req) {
        Expense old = requireExpense(expenseId);
        Ledger ledger = requireLedger(old.getLedgerId());
        assertCanModify(old, ledger, operatorId);

        // 新版本值先行校验（纯内存），避免"更新失败了才发现有非法分摊"
        ShareType type = uniformShareType(req.splits());
        List<ExpenseSplit> newSplits = computeSplits(req.amount(), req.paidBy(), req.splits(), type);
        assertAllMembers(old.getLedgerId(), req.paidBy(), newSplits);
        // 关键：先把"旧付款人/旧金额/旧分摊"快照下来再改库——回滚净额必须按旧事实反着算，
        // 若等 updateById 之后再读，读到的是新值，回滚就回滚错人了（这是本流程最隐蔽的坑）
        Long oldPaidBy = old.getPaidBy();
        BigDecimal oldAmount = old.getAmount();
        List<ExpenseSplit> oldSplits = listSplits(expenseId);

        // 乐观锁修复（全量测试实测根因）：原代码写的是 upd.setVersion(old.getVersion())——
        // 用"数据库当前版本"做 WHERE，条件永远成立，两个携带旧 version 的并发 PUT 都会成功，
        // 冲突检测形同虚设（后写覆盖先写）。乐观锁的 WHERE 必须用【客户端提交的版本号】：
        // 与库中当前值不一致时 updateById 影响 0 行 -> 409。
        // 这里再做一次先行比对（与 #8 删除的语义对齐），让最常见的"读-改-写过期"路径
        // 在进入多表写入前就快速失败；真正的并发窗口仍由下面的 0 行检测兜底。
        if (!req.version().equals(old.getVersion())) {
            throw BizException.conflict("该账目已被他人修改，请刷新后重试");
        }
        // updateById 自动追加 WHERE version = 实体版本值，SET version = 版本+1。
        // 返回 0 行 = 并发对手已提交修改：抛 409（计划书"冲突提示而非静默覆盖"）
        Expense upd = new Expense();
        upd.setId(old.getId());
        upd.setVersion(req.version()); // WHERE 条件用客户端带来的旧版本号（修复点）
        upd.setTitle(req.title());
        upd.setAmount(req.amount().setScale(2, RoundingMode.UNNECESSARY));
        upd.setPaidBy(req.paidBy());
        upd.setExpenseDate(req.expenseDate());
        upd.setDueDate(req.dueDate());
        int rows = expenseMapper.updateById(upd);
        if (rows == 0) {
            throw BizException.conflict("该账目已被他人修改，请刷新后重试");
        }

        // 回滚旧净额（delta 取反）- 删旧 split - 插新 split - 应用新净额
        reverseBalances(old.getLedgerId(), oldPaidBy, oldAmount, oldSplits);
        splitMapper.delete(Wrappers.<ExpenseSplit>lambdaQuery().eq(ExpenseSplit::getExpenseId, expenseId));
        for (ExpenseSplit s : newSplits) {
            s.setExpenseId(expenseId);
            splitMapper.insert(s);
        }
        applyBalances(old.getLedgerId(), upd.getPaidBy(), upd.getAmount(), newSplits);
        evictCaches(oldSplits, oldPaidBy);
        evictCaches(newSplits, req.paidBy());
    }

    // ==================== #8 删一笔 ====================

    /**
     * 删一笔：显式 version 比对（DELETE 请求不便带实体，用查询参数）后
     * 回滚净额 -&gt; 物理删 split -&gt; 逻辑删 expense，同事务。
     */
    @Transactional
    @Audited(action = "EXPENSE_DELETE", targetType = "EXPENSE", targetIdExpr = "#expenseId")
    public void delete(Long expenseId, Long operatorId, Integer version) {
        Expense e = requireExpense(expenseId);
        Ledger ledger = requireLedger(e.getLedgerId());
        assertCanModify(e, ledger, operatorId);
        // DELETE 语义下 MP 乐观锁不织入 deleteById 的 WHERE，这里手工比对——
        // 冲突语义与 #7 一致：409，防"一人删一人改"互相踩踏
        if (version == null || !version.equals(e.getVersion())) {
            throw BizException.conflict("该账目已被他人修改，请刷新后重试");
        }

        List<ExpenseSplit> splits = listSplits(expenseId);
        reverseBalances(e.getLedgerId(), e.getPaidBy(), e.getAmount(), splits);
        splitMapper.delete(Wrappers.<ExpenseSplit>lambdaQuery().eq(ExpenseSplit::getExpenseId, expenseId));
        expenseMapper.deleteById(expenseId); // 命中全局逻辑删配置：UPDATE deleted=1
        evictCaches(splits, e.getPaidBy());
    }

    // ==================== #9 明细分页 ====================

    /**
     * 账本明细分页：走 idx_ledger_date(ledger_id, expense_date) 组合索引，
     * 日期倒序。split 用 in(expenseIds) 一次补齐整页——N+1 的反面教材修法。
     */
    @Transactional(readOnly = true)
    public ExpenseDto.PageVO<ExpenseDto.ExpenseVO> page(Long ledgerId, Long operatorId, long page, long size) {
        ledgerService.assertMember(ledgerId, operatorId);
        long safeSize = Math.min(Math.max(size, 1), 100); // 页大小钳制，防恶意 size=10000 打穿内存
        Page<Expense> p = expenseMapper.selectPage(
                Page.of(Math.max(page, 1), safeSize),
                Wrappers.<Expense>lambdaQuery()
                        .eq(Expense::getLedgerId, ledgerId)
                        .orderByDesc(Expense::getExpenseDate)
                        .orderByDesc(Expense::getId));
        List<Expense> records = p.getRecords();
        Map<Long, List<ExpenseSplit>> byExpense = new LinkedHashMap<>();
        if (!records.isEmpty()) {
            for (ExpenseSplit s : splitMapper.selectList(Wrappers.<ExpenseSplit>lambdaQuery()
                    .in(ExpenseSplit::getExpenseId, records.stream().map(Expense::getId).toList()))) {
                byExpense.computeIfAbsent(s.getExpenseId(), k -> new ArrayList<>()).add(s);
            }
        }
        List<ExpenseDto.ExpenseVO> vos = records.stream()
                .map(e -> toVO(e, byExpense.getOrDefault(e.getId(), List.of())))
                .toList();
        return new ExpenseDto.PageVO<>(vos, p.getTotal(), p.getCurrent(), p.getSize());
    }

    // ==================== 分摊计算（静态可测的纯函数） ====================

    /**
     * 把请求里的分摊行算成"精确到分"的落库行。
     *
     * <p>余数规则（计划书 10 风险，明确写进实现）：</p>
     * <ul>
     *   <li>EQUAL：每人 base = total/n（向下取整），余数 rem 全部归付款人；
     *       付款人不在参与人列表时，rem&gt;0 会额外生成一行付款人承担记录；</li>
     *   <li>RATIO：按 share 权重最大余数法分配（floor + 按余数从大到小补给前几名），
     *       保证 SUM(行)=总额 恰好成立；</li>
     *   <li>AMOUNT：各行即金额，直接校验总和=总额。</li>
     * </ul>
     */
    static List<ExpenseSplit> computeSplits(BigDecimal totalYuan, Long paidBy,
                                            List<ExpenseDto.SplitReq> reqs, ShareType type) {
        long totalCents = toCents(totalYuan);
        if (totalCents <= 0) {
            throw badRequest("支出总额必须为正");
        }
        List<ExpenseSplit> rows = new ArrayList<>();
        switch (type) {
            case EQUAL -> {
                int n = reqs.size();
                long base = totalCents / n;
                long rem = totalCents % n;
                for (ExpenseDto.SplitReq r : reqs) {
                    rows.add(splitRow(r.userId(), "EQUAL", base));
                }
                // 余数归付款人：先找付款人在参与人里的行直接加；不在且确有余数才补一行
                ExpenseSplit payerRow = rows.stream()
                        .filter(s -> s.getUserId().equals(paidBy)).findFirst().orElse(null);
                if (payerRow != null) {
                    payerRow.setAmount(centsToYuan(payerRow.getAmount()
                            .movePointRight(2).longValue() + rem));
                } else if (rem > 0) {
                    rows.add(splitRow(paidBy, "EQUAL", rem));
                }
            }
            case RATIO -> {
                long[] cents = largestRemainder(totalCents, reqs);
                for (int i = 0; i < reqs.size(); i++) {
                    rows.add(splitRow(reqs.get(i).userId(), "RATIO", cents[i]));
                }
            }
            case AMOUNT -> {
                long sum = 0;
                for (ExpenseDto.SplitReq r : reqs) {
                    long c = toCents(r.share());
                    sum += c;
                    rows.add(splitRow(r.userId(), "AMOUNT", c));
                }
                // 校验分摊总额=支出总额（FR-06）：不等直接拒绝，绝不允许"差一分"入库
                if (sum != totalCents) {
                    throw badRequest("分摊金额之和 " + centsToYuan(sum) + " 不等于支出总额 " + centsToYuan(totalCents));
                }
            }
        }
        mergeDuplicateRows(rows);
        return rows;
    }

    /**
     * 最大余数法：先按比例 floor，再把余下的分按"小数部分从大到小"逐分补给，
     * 保证总额精确且不引入随机性（tie 时按入参顺序，稳定可复现）。
     */
    private static long[] largestRemainder(long totalCents, List<ExpenseDto.SplitReq> reqs) {
        BigDecimal totalShare = BigDecimal.ZERO;
        for (ExpenseDto.SplitReq r : reqs) {
            if (r.share() == null || r.share().signum() <= 0) {
                throw badRequest("RATIO 分摊的份额必须为正数");
            }
            totalShare = totalShare.add(r.share());
        }
        long[] cents = new long[reqs.size()];
        BigDecimal[] fracs = new BigDecimal[reqs.size()]; // 记录小数余数用于排序补给
        long used = 0;
        for (int i = 0; i < reqs.size(); i++) {
            BigDecimal exact = totalShare.equals(BigDecimal.ZERO)
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(totalCents).multiply(reqs.get(i).share())
                            .divide(totalShare, 10, RoundingMode.DOWN);
            cents[i] = exact.longValue(); // floor
            fracs[i] = exact.subtract(BigDecimal.valueOf(cents[i]));
            used += cents[i];
        }
        long rem = totalCents - used; // 0 <= rem < n
        // 余数从大到小的下标依次 +1 分
        Integer[] order = new Integer[reqs.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> fracs[b].compareTo(fracs[a]));
        for (int k = 0; k < rem; k++) {
            cents[order[k % order.length]]++;
        }
        return cents;
    }

    /** 同一 userId 出现多行（非法但容错合并）：合并金额，UK(expense_id,user_id) 决定必须唯一 */
    private static void mergeDuplicateRows(List<ExpenseSplit> rows) {
        Map<Long, ExpenseSplit> uniq = new LinkedHashMap<>();
        for (ExpenseSplit s : rows) {
            ExpenseSplit prev = uniq.get(s.getUserId());
            if (prev == null) {
                uniq.put(s.getUserId(), s);
            } else {
                // 同一人重复行：金额合并（AMOUNT 语义的合理扩展），保留首行的 shareType
                prev.setAmount(prev.getAmount().add(s.getAmount()));
            }
        }
        rows.clear();
        rows.addAll(uniq.values());
    }

    /** 全账本只允许一种分摊方式混用会让"余数归谁"语义含糊，MVP 直接禁止 */
    private static ShareType uniformShareType(List<ExpenseDto.SplitReq> reqs) {
        ShareType type = null;
        for (ExpenseDto.SplitReq r : reqs) {
            ShareType t;
            try {
                t = ShareType.valueOf(r.shareType().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw badRequest("不支持的分摊方式: " + r.shareType());
            }
            if (type == null) {
                type = t;
            } else if (type != t) {
                throw badRequest("同一笔支出不能混用多种分摊方式");
            }
        }
        return type;
    }

    /** 元 -&gt; 分：超过两位小数视为非法金额 */
    private static long toCents(BigDecimal yuan) {
        if (yuan == null) {
            throw badRequest("金额不能为空");
        }
        if (yuan.stripTrailingZeros().scale() > 2) {
            throw badRequest("金额最多两位小数: " + yuan.toPlainString());
        }
        return yuan.movePointRight(2).setScale(0).longValueExact();
    }

    /** 分 -&gt; 元（两位小数） */
    private static BigDecimal centsToYuan(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private static ExpenseSplit splitRow(Long userId, String shareType, long cents) {
        ExpenseSplit s = new ExpenseSplit();
        s.setUserId(userId);
        s.setShareType(shareType);
        s.setAmount(centsToYuan(cents));
        return s;
    }

    // ==================== 内部小流程 ====================

    /**
     * 应用一笔支出的净额（正向）：付款人 +total，各分摊行 -amount。
     * 净额增量语义只在 5.1 定义过一次，这里与 reverseBalances 严格互逆。
     */
    private void applyBalances(Long ledgerId, Long paidBy, BigDecimal total,
                               List<ExpenseSplit> splits) {
        balanceService.applyDelta(ledgerId, paidBy, total);
        for (ExpenseSplit s : splits) {
            balanceService.applyDelta(ledgerId, s.getUserId(), s.getAmount().negate());
        }
    }

    /**
     * 回滚一笔支出的净额（改/删第一步）：付款人 -total、各分摊人 +amount 的逆运算。
     *
     * <p>total 用调用方传入的旧快照而不是 SUM(旧 split)：二者在数据健康时相等，
     * 但回滚以"当初入账了什么"为准更稳（历史脏 split 不会放大回滚量）。</p>
     */
    private void reverseBalances(Long ledgerId, Long oldPaidBy, BigDecimal oldTotal,
                                 List<ExpenseSplit> oldSplits) {
        balanceService.applyDelta(ledgerId, oldPaidBy, oldTotal.negate());
        for (ExpenseSplit s : oldSplits) {
            balanceService.applyDelta(ledgerId, s.getUserId(), s.getAmount());
        }
    }

    /** 清所有相关用户的"我的账本列表"缓存（8.1 尾步：改账后删缓存，不是更缓存） */
    private void evictCaches(List<ExpenseSplit> splits, Long paidBy) {
        Set<Long> users = new HashSet<>();
        if (paidBy != null && paidBy > 0) {
            users.add(paidBy);
        }
        for (ExpenseSplit s : splits) {
            users.add(s.getUserId());
        }
        users.forEach(ledgerService::evictLedgerListCache);
    }

    private List<ExpenseSplit> listSplits(Long expenseId) {
        return splitMapper.selectList(
                Wrappers.<ExpenseSplit>lambdaQuery().eq(ExpenseSplit::getExpenseId, expenseId));
    }

    /** 付款人与全部参与人必须已是账本成员：提前失败，避免外键式孤儿数据 */
    private void assertAllMembers(Long ledgerId, Long paidBy, List<ExpenseSplit> splits) {
        ledgerService.assertMember(ledgerId, paidBy);
        for (ExpenseSplit s : splits) {
            ledgerService.assertMember(ledgerId, s.getUserId());
        }
    }

    /** 改/删权限（计划书 7 章）：付款人本人或账本 owner，其他人 403 */
    private void assertCanModify(Expense e, Ledger ledger, Long operatorId) {
        if (!operatorId.equals(e.getPaidBy()) && !operatorId.equals(ledger.getOwnerId())) {
            throw BizException.forbidden("只有付款人本人或账本创建者可以修改/删除该笔支出");
        }
    }

    private Expense requireExpense(Long expenseId) {
        Expense e = expenseMapper.selectById(expenseId);
        if (e == null) {
            throw BizException.notFound("支出");
        }
        return e;
    }

    private Ledger requireLedger(Long ledgerId) {
        Ledger l = ledgerMapper.selectById(ledgerId);
        if (l == null) {
            throw BizException.notFound("账本");
        }
        return l;
    }

    private static BizException badRequest(String msg) {
        return new BizException(40001, 400, msg);
    }

    private ExpenseDto.ExpenseVO toVO(Expense e, List<ExpenseSplit> splits) {
        List<ExpenseDto.SplitVO> svos = splits.stream()
                .map(s -> new ExpenseDto.SplitVO(s.getUserId(), s.getShareType(), s.getAmount()))
                .toList();
        return new ExpenseDto.ExpenseVO(e.getId(), e.getLedgerId(), e.getTitle(), e.getAmount(),
                e.getPaidBy(), e.getExpenseDate(), e.getDueDate(), e.getVersion(), svos);
    }
}
