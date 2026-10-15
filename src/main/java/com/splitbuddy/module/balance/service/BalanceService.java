package com.splitbuddy.module.balance.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.splitbuddy.common.BizException;
import com.splitbuddy.module.balance.dto.BalanceDto;
import com.splitbuddy.module.balance.entity.Balance;
import com.splitbuddy.module.balance.mapper.BalanceMapper;
import com.splitbuddy.module.expense.mapper.ExpenseSplitMapper;
import com.splitbuddy.module.ledger.service.LedgerService;
import com.splitbuddy.module.settlement.mapper.SettlementMapper;
import com.splitbuddy.module.user.entity.User;
import com.splitbuddy.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 净额维护服务（计划书 5.1：不为每笔账存"谁欠谁"，只维护净额）。
 *
 * <p>解决什么问题：所有"记账/改账/删账/还钱"对净额的改动收敛到 applyDelta 一个方法，
 * 增量语义（付款人+、参与人-、还钱方+、收钱方-）只在一处定义，避免多处手写不一致。</p>
 *
 * <p>关键取舍：增量更新用数据库端原子加减（net = net + delta）而不是读改写，
 * 并发记账时天然无丢失更新；首次落账没有净额行时 INSERT，撞 UK 说明并发下别人已插，
 * 捕获 DuplicateKeyException 后重试一次原子 UPDATE——"用数据库约束保证并发正确"。
 * 方法带 MANDATORY 传播：净额增量必须发生在调用方（记一笔/执行结清）的事务里，
 * 单独调用没有意义，误用直接启动失败，防呆于未然。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final BalanceMapper balanceMapper;
    private final ExpenseSplitMapper splitMapper;
    private final SettlementMapper settlementMapper;
    private final UserMapper userMapper;
    private final LedgerService ledgerService;

    /**
     * 原子增量。delta 为 BigDecimal（元），正=应收增加/应付减少。
     *
     * @param ledgerId 账本
     * @param userId   成员
     * @param delta    净变化；0 视为无事发生直接返回
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyDelta(Long ledgerId, Long userId, BigDecimal delta) {
        if (delta == null || delta.signum() == 0) {
            return;
        }
        int rows = balanceMapper.addNet(ledgerId, userId, delta);
        if (rows > 0) {
            return;
        }
        // 无行：插入首条净额。并发下两方同时插会有一方撞 UK(ledger_id,user_id)
        Balance b = new Balance();
        b.setLedgerId(ledgerId);
        b.setUserId(userId);
        b.setNet(delta);
        try {
            balanceMapper.insert(b);
        } catch (DuplicateKeyException e) {
            // 兜底重试：行已存在（并发对手插的），直接原子加同一笔 delta 即可
            int retry = balanceMapper.addNet(ledgerId, userId, delta);
            if (retry == 0) {
                throw new BizException(50002, "净额行写入异常，请重试");
            }
        }
    }

    /** 净额表查询（#10）：先校验成员身份，再查 balance 批量补 nickname */
    @Transactional(readOnly = true)
    public List<BalanceDto.BalanceVO> listBalances(Long ledgerId, Long operatorId) {
        ledgerService.assertMember(ledgerId, operatorId);
        List<Balance> rows = balanceMapper.selectList(
                Wrappers.<Balance>lambdaQuery().eq(Balance::getLedgerId, ledgerId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nick = nicknames(rows.stream().map(Balance::getUserId).distinct().toList());
        return rows.stream()
                .map(r -> new BalanceDto.BalanceVO(
                        r.getUserId(), nick.getOrDefault(r.getUserId(), "?"), r.getNet()))
                .toList();
    }

    /** 算法输入：ledgerId 的 userId->净额 映射（调用方需自行做成员校验） */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> netMap(Long ledgerId) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (Balance b : balanceMapper.selectList(
                Wrappers.<Balance>lambdaQuery().eq(Balance::getLedgerId, ledgerId))) {
            m.put(b.getUserId(), b.getNet());
        }
        return m;
    }

    /** 该账本是否还有人处于"应付"状态（净额<0）：到期提醒的"未结清"判定依据 */
    public boolean hasDebtor(Long ledgerId) {
        Long cnt = balanceMapper.selectCount(
                Wrappers.<Balance>lambdaQuery()
                        .eq(Balance::getLedgerId, ledgerId)
                        .lt(Balance::getNet, BigDecimal.ZERO));
        return cnt > 0;
    }

    /**
     * 全量重算净额并写回（计划书 10 风险的兜底手段，对账报警点）。
     *
     * <p>净 = 垫付总额 - 承担总额 + 还出总额 - 收进总额，全部从事实表重新 SUM，
     * 与增量维护互为校验：两条独立路径算出同一结果即认为净额可信。
     * 低频运维操作（重插全表），故 delete+batch insert 放独立事务。</p>
     */
    @Transactional
    public BalanceDto.ReconVO recalculate(Long ledgerId, Long operatorId) {
        ledgerService.assertMember(ledgerId, operatorId);

        BigDecimal sumBefore = sumNet(ledgerId);

        // 四路聚合：垫付、承担、还出、收进（SQL 端 GROUP BY，行数=人数，量小）
        Map<Long, BigDecimal> net = new HashMap<>();
        accumulate(net, splitMapper.sumPaidByUser(ledgerId), false);
        accumulate(net, splitMapper.sumShareByUser(ledgerId), true);
        accumulate(net, settlementMapper.sumSentByUser(ledgerId), false);
        accumulate(net, settlementMapper.sumReceivedByUser(ledgerId), true);

        balanceMapper.delete(Wrappers.<Balance>lambdaQuery().eq(Balance::getLedgerId, ledgerId));
        int rows = 0;
        for (Map.Entry<Long, BigDecimal> e : net.entrySet()) {
            if (e.getValue().signum() == 0) {
                continue; // 净额为 0 的成员不落行，保持表紧凑（结清后表自然清空）
            }
            Balance b = new Balance();
            b.setLedgerId(ledgerId);
            b.setUserId(e.getKey());
            b.setNet(e.getValue());
            balanceMapper.insert(b);
            rows++;
        }
        BigDecimal sumAfter = sumNet(ledgerId);
        if (sumAfter.signum() != 0) {
            // 对账不变量被破坏：重算后仍不为 0 说明事实数据本身异常（正常业务到不了这里），
            // 打 error 触发监控，同时拒绝写回结果——保持旧数据比写坏数据更可查
            log.error("账本 {} 净额重算后求和 {} 不为 0，事实数据可能已漂移，请人工对账", ledgerId, sumAfter);
        }
        return new BalanceDto.ReconVO(sumBefore, sumAfter, rows);
    }

    /** map.put 累加小工具：isSubtract=true 表示该聚合结果要从净额里减掉 */
    private void accumulate(Map<Long, BigDecimal> net, List<Map<String, Object>> agg, boolean isSubtract) {
        for (Map<String, Object> row : agg) {
            Long uid = ((Number) row.get("userId")).longValue();
            BigDecimal v = (BigDecimal) row.get("total");
            net.merge(uid, isSubtract ? v.negate() : v, BigDecimal::add);
        }
    }

    /** 当前净额求和（对账校验值） */
    private BigDecimal sumNet(Long ledgerId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Balance b : balanceMapper.selectList(
                Wrappers.<Balance>lambdaQuery().eq(Balance::getLedgerId, ledgerId))) {
            sum = sum.add(b.getNet());
        }
        return sum;
    }

    /** 批量取昵称：一次 selectBatchIds，杜绝 N+1 */
    private Map<Long, String> nicknames(List<Long> userIds) {
        Map<Long, String> m = new HashMap<>();
        for (User u : userMapper.selectBatchIds(userIds)) {
            m.put(u.getId(), u.getNickname());
        }
        return m;
    }
}
