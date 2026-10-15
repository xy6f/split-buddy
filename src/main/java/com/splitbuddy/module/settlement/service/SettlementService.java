package com.splitbuddy.module.settlement.service;

import com.splitbuddy.aop.Audited;
import com.splitbuddy.common.BizException;
import com.splitbuddy.domain.debt.DebtSolver;
import com.splitbuddy.domain.debt.DebtSolver.Transfer;
import com.splitbuddy.module.balance.service.BalanceService;
import com.splitbuddy.module.ledger.service.LedgerService;
import com.splitbuddy.module.settlement.dto.SettlementDto;
import com.splitbuddy.module.settlement.entity.Settlement;
import com.splitbuddy.module.settlement.mapper.SettlementMapper;
import com.splitbuddy.module.user.entity.User;
import com.splitbuddy.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结清业务（FR-05/FR-07，接口 #11~#13）。
 *
 * <p>解决什么问题：把 DebtSolver 的纯算法输出接上事务落库——"执行方案"就是
 * 把每条 Transfer 变成 t_settlement 行并做净额增量（债务人 +m、债权人 -m），
 * 全部一张转账共享一个事务：要么全结清要么全无，绝不允许"结了一半"的中间态。</p>
 *
 * <p>关键取舍：#11 查询方案是只读的、无副作用——每次实时基于当前净额计算，
 * 不做"方案缓存/方案ID"。理由：净额随时在变，缓存方案会给出过期转账（执行时
 * 金额和现实对不上）；n≤20 时算法毫秒级，现算比管理缓存生命周期便宜得多。
 * 因此 #12 执行时重新算一遍方案，两次调用之间账若有变动，执行的是"最新"方案。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementMapper settlementMapper;
    private final BalanceService balanceService;
    private final LedgerService ledgerService;
    private final UserMapper userMapper;

    /** #11 结清方案：成员校验 + DebtSolver 纯函数 + 昵称拼装 */
    public SettlementDto.PlanVO plan(Long ledgerId, Long operatorId) {
        ledgerService.assertMember(ledgerId, operatorId);
        Map<Long, BigDecimal> nets = balanceService.netMap(ledgerId);
        List<Transfer> transfers = DebtSolver.solve(nets);
        Map<Long, String> nick = nicknames(transfers);
        List<SettlementDto.TransferVO> vos = transfers.stream()
                .map(t -> new SettlementDto.TransferVO(
                        t.fromUserId(), nick.getOrDefault(t.fromUserId(), "?"),
                        t.toUserId(), nick.getOrDefault(t.toUserId(), "?"),
                        t.amount()))
                .toList();
        // 算法标识按规模说明取舍：n<=20 走子集 DP（greedy+subset），更大退化为纯贪心
        String algorithm = nets.size() <= 20 ? "greedy+subset" : "greedy";
        return new SettlementDto.PlanVO(vos, vos.size(), algorithm);
    }

    /**
     * #12 一键执行方案：confirm 必须为 true（防误触的显式确认位），
     * 事务内逐条 insert settlement(EXEC_PLAN) + 净额双向增量 + 清缓存。
     */
    @Transactional
    @Audited(action = "SETTLEMENT_EXECUTE", targetType = "SETTLEMENT", targetIdExpr = "#ledgerId")
    public List<SettlementDto.SettlementVO> executePlan(Long ledgerId, Long operatorId, boolean confirm) {
        ledgerService.assertMember(ledgerId, operatorId);
        if (!confirm) {
            throw new BizException(40002, 400, "执行结清方案必须显式确认（confirm=true）");
        }
        Map<Long, BigDecimal> nets = balanceService.netMap(ledgerId);
        List<Transfer> transfers = DebtSolver.solve(nets);
        List<SettlementDto.SettlementVO> out = new java.util.ArrayList<>();
        for (Transfer t : transfers) {
            out.add(persistOne(ledgerId, t.fromUserId(), t.toUserId(), t.amount(), "EXEC_PLAN"));
        }
        log.info("账本 {} 执行结清方案 {} 条转账", ledgerId, out.size());
        return out;
    }

    /** #13 手动登记还钱：校验方向合理性后落库 + 净额增量 */
    @Transactional
    @Audited(action = "SETTLEMENT_MANUAL", targetType = "SETTLEMENT", targetIdExpr = "#ledgerId")
    public SettlementDto.SettlementVO manual(Long ledgerId, Long operatorId, SettlementDto.ManualReq req) {
        ledgerService.assertMember(ledgerId, operatorId);
        if (req.fromUser().equals(req.toUser())) {
            throw new BizException(40003, 400, "还款方与收款方不能是同一人");
        }
        ledgerService.assertMember(ledgerId, req.fromUser());
        ledgerService.assertMember(ledgerId, req.toUser());

        // 方向防呆（计划书权限精神的延伸）：还钱必须"欠钱的人还给被欠的人"，
        // 且不得超过对方的实际欠款——防止把净额配平表越还越歪
        Map<Long, BigDecimal> nets = balanceService.netMap(ledgerId);
        BigDecimal fromNet = nets.getOrDefault(req.fromUser(), BigDecimal.ZERO);
        BigDecimal toNet = nets.getOrDefault(req.toUser(), BigDecimal.ZERO);
        BigDecimal amount = req.amount().setScale(2, RoundingMode.UNNECESSARY);
        if (fromNet.signum() >= 0) {
            throw new BizException(40004, 400, "该成员当前不欠款，无法登记其还款");
        }
        if (amount.compareTo(fromNet.negate()) > 0) {
            throw new BizException(40005, 400, "还款金额超过该成员欠款总额 " + fromNet.negate());
        }
        if (toNet.signum() <= 0) {
            throw new BizException(40006, 400, "收款方当前不处于应收状态");
        }
        return persistOne(ledgerId, req.fromUser(), req.toUser(), amount, "MANUAL");
    }

    /** 落一条转账 + 净额增量（债务 +m 应收 -m 见 5.1）+ 清缓存，#12/#13 共用 */
    private SettlementDto.SettlementVO persistOne(Long ledgerId, Long from, Long to,
                                                  BigDecimal amount, String source) {
        Settlement s = new Settlement();
        s.setLedgerId(ledgerId);
        s.setFromUser(from);
        s.setToUser(to);
        s.setAmount(amount);
        s.setSource(source);
        settlementMapper.insert(s);
        // 还钱 r：balance[from] += m（应付减少），balance[to] -= m（应收减少）
        balanceService.applyDelta(ledgerId, from, amount);
        balanceService.applyDelta(ledgerId, to, amount.negate());
        ledgerService.evictLedgerListCache(from);
        ledgerService.evictLedgerListCache(to);
        return new SettlementDto.SettlementVO(s.getId(), from, to, amount, source);
    }

    /** 批量取转账双方昵称：set 去重后一次查库 */
    private Map<Long, String> nicknames(List<Transfer> transfers) {
        Map<Long, String> m = new HashMap<>();
        List<Long> ids = transfers.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.fromUserId(), t.toUserId()))
                .distinct().toList();
        if (ids.isEmpty()) {
            return m;
        }
        for (User u : userMapper.selectBatchIds(ids)) {
            m.put(u.getId(), u.getNickname());
        }
        return m;
    }
}
