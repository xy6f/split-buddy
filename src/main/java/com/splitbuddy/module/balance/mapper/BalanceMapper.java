package com.splitbuddy.module.balance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.balance.entity.Balance;

import java.math.BigDecimal;

/**
 * 净额表 Mapper。
 *
 * <p>核心是 addNet 这条手写 SQL：UPDATE ... SET net = net + #{delta}，
 * 数据库端原子加减。不用"SELECT 再 setNet 再 updateById"的读改写，
 * 因为并发记账时两条增量会互相覆盖（丢失更新）——净额维护的正确性
 * 靠这条 SQL 而不是靠乐观锁（乐观锁留给"改一笔"这种要整体回滚重算的场景）。</p>
 */
public interface BalanceMapper extends BaseMapper<Balance> {

    /**
     * 原子增量：命中 UK(ledger_id,user_id) 定位行。
     *
     * @return 影响行数：1=更新成功；0=该成员还没有净额行，调用方负责 INSERT 后重试
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_balance SET net = net + #{delta} "
                    + "WHERE ledger_id = #{ledgerId} AND user_id = #{userId}")
    int addNet(Long ledgerId, Long userId, BigDecimal delta);
}
