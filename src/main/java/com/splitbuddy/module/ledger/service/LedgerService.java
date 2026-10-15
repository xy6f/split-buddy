package com.splitbuddy.module.ledger.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitbuddy.common.BizException;
import com.splitbuddy.common.RedisSafeService;
import com.splitbuddy.module.balance.entity.Balance;
import com.splitbuddy.module.balance.mapper.BalanceMapper;
import com.splitbuddy.module.ledger.dto.LedgerDto;
import com.splitbuddy.module.ledger.entity.Ledger;
import com.splitbuddy.module.ledger.entity.LedgerMember;
import com.splitbuddy.module.ledger.mapper.LedgerMapper;
import com.splitbuddy.module.ledger.mapper.LedgerMemberMapper;
import com.splitbuddy.module.user.entity.User;
import com.splitbuddy.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账本业务：创建、邀请成员、列表、成员断言。
 *
 * <p>权限模型说明（面试点）：本项目不用 RBAC 角色表，而是"资源级成员制"——
 * 一切账本内操作先过 assertMember。它放在 Service 层第一行（而非 Controller），
 * 保证无论从哪个入口（HTTP/未来的定时任务）调用都强制生效。</p>
 *
 * <p>W4 缓存：myLedgers 走 Redis 读穿缓存（主动失效 + 60s TTL 兜底）。
 * 一致性策略是"改账后删缓存"而不是"更缓存"（计划书 11.6：双写要同步的字段太多，
 * 删掉让下一次读自然重建；缓存只是加速视图，永远不是权威来源）。
 * 所有 Redis 操作经 RedisSafeService 降级容错——Redis 挂掉等于没有缓存，
 * 应用照常从 MySQL 读，绝不因缓存不可用而报错。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    /** 账本列表缓存键前缀：按"用户"维度缓存我的账本列表，读写都 O(1) 定位 */
    private static final String CACHE_KEY_PREFIX = "ledger:list:";
    /** TTL 60 秒：建账本/入成员/记一笔都主动失效，TTL 只兜住"漏网失效"的旧读 */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper memberMapper;
    private final UserMapper userMapper;
    // myNet 取数直接注入净额 Mapper 而不是 BalanceService：后者依赖本类做成员断言，
    // Service 间反向依赖会形成启动环；这里只是单表查询，用 Mapper 最干净
    private final BalanceMapper balanceMapper;
    private final RedisSafeService redisSafe;
    private final ObjectMapper objectMapper;

    /**
     * 创建账本：插账本 + 把 owner 自动入成员表。
     * 两条 INSERT 必须同事务——owner 不在成员表会导致后续所有操作被自己的 assertMember 拒绝。
     */
    @Transactional
    public LedgerDto.LedgerVO create(Long ownerId, LedgerDto.CreateReq req) {
        Ledger l = new Ledger();
        l.setName(req.name());
        l.setOwnerId(ownerId);
        l.setCurrency("CNY");
        ledgerMapper.insert(l);
        addMember(l.getId(), ownerId);
        evictLedgerListCache(ownerId);
        // 新账本尚无任何净额，myNet 恒为 0
        return toVO(l, BigDecimal.ZERO);
    }

    /** 邀请：必须是成员才能拉人；按用户名查，不存在直接报错（MVP 简化流程） */
    @Transactional
    public void invite(Long ledgerId, Long operatorId, String username) {
        assertMember(ledgerId, operatorId);
        User u = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (u == null) {
            throw BizException.notFound("用户 " + username);
        }
        addMember(ledgerId, u.getId());
        // 被邀请人的"我的账本"多了一条，操作者与新人两边的缓存都要失效
        evictLedgerListCache(operatorId);
        evictLedgerListCache(u.getId());
    }

    /**
     * 我的账本列表（#5）：读穿缓存。
     * 命中直接反序列化返回；未命中查库后回填。RedisSafe 保证任何一环失败
     * 都退化为"每次查库"，功能正确性不依赖 Redis。
     */
    public List<LedgerDto.LedgerVO> myLedgers(Long userId) {
        String key = CACHE_KEY_PREFIX + userId;
        String cached = redisSafe.get(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<LedgerDto.LedgerVO>>() {});
            } catch (Exception e) {
                // 缓存内容坏了（结构演进遗留等）：当作未命中，回源重建，不向用户抛错
                log.warn("账本列表缓存反序列化失败，按未命中处理 userId={}", userId);
            }
        }
        List<LedgerDto.LedgerVO> list = queryMyLedgers(userId);
        try {
            redisSafe.set(key, objectMapper.writeValueAsString(list), CACHE_TTL);
        } catch (Exception e) {
            log.warn("账本列表缓存序列化失败，跳过回填 userId={}", userId);
        }
        return list;
    }

    /** 主动失效：记一笔/改账/结清/入成员等一切影响"我的账本视图"的写操作都调用 */
    public void evictLedgerListCache(Long userId) {
        redisSafe.delete(CACHE_KEY_PREFIX + userId);
    }

    /**
     * 真正的库查询路径：三次简单查询（成员表拿ID集合 -> 账本表批量取 -> 净额表按
     * (user, ledger in ids) 取 myNet），比 join 好维护。净额行只在"有欠款关系"时存在，
     * 结清后自然消失，因此无行等于 0。myNet 并入缓存值是正确的：任何改变本人净额的
     * 写操作（记/改/删/结清）都会 evict 相关用户的列表缓存。
     */
    private List<LedgerDto.LedgerVO> queryMyLedgers(Long userId) {
        List<Long> ids = memberMapper.selectMyLedgerIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> netByLedger = new HashMap<>();
        for (Balance b : balanceMapper.selectList(Wrappers.<Balance>lambdaQuery()
                .eq(Balance::getUserId, userId)
                .in(Balance::getLedgerId, ids))) {
            netByLedger.put(b.getLedgerId(), b.getNet());
        }
        return ledgerMapper.selectBatchIds(ids).stream()
                .map(l -> toVO(l, netByLedger.getOrDefault(l.getId(), BigDecimal.ZERO)))
                .toList();
    }

    /**
     * 账本成员名单（GET /api/ledgers/{id}/members）：先走 assertMember——
     * 名单本身就是账本内信息，非成员不该看到"这个账本里有谁"（404/403 语义同断言）。
     * 一次 join 即库查询上限，不必做读穿缓存：与 myLedgers 的策略刻意不同，按需取舍。
     */
    public List<LedgerDto.MemberVO> members(Long ledgerId, Long requesterId) {
        assertMember(ledgerId, requesterId);
        return memberMapper.selectLedgerMembers(ledgerId);
    }

    /** 成员断言：不存在=404，不是成员=403。被计划中 @LedgerMember AOP 注解复用的底层方法 */
    public void assertMember(Long ledgerId, Long userId) {
        if (ledgerMapper.selectById(ledgerId) == null) {
            throw BizException.notFound("账本");
        }
        Long cnt = memberMapper.selectCount(
                Wrappers.<LedgerMember>lambdaQuery()
                        .eq(LedgerMember::getLedgerId, ledgerId)
                        .eq(LedgerMember::getUserId, userId));
        if (cnt == 0) {
            throw BizException.forbidden("你不是该账本成员");
        }
    }

    /** 幂等加成员：重复邀请靠唯一键兜底，捕获后视为成功（语义："反正成员了"） */
    private void addMember(Long ledgerId, Long userId) {
        try {
            LedgerMember m = new LedgerMember();
            m.setLedgerId(ledgerId);
            m.setUserId(userId);
            memberMapper.insert(m);
        } catch (DuplicateKeyException ignored) {
            // 已是成员，静默通过
        }
    }

    private LedgerDto.LedgerVO toVO(Ledger l, BigDecimal myNet) {
        return new LedgerDto.LedgerVO(l.getId(), l.getName(), l.getOwnerId(), l.getCurrency(), myNet);
    }
}
