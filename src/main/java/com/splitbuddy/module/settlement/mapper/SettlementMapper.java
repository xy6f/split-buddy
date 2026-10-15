package com.splitbuddy.module.settlement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.settlement.entity.Settlement;

import java.util.List;

/**
 * 转账记录 Mapper。
 * sumSettledByUser 供净额全量重算：净 = 垫付 - 承担 + 还出 - 收进。
 */
public interface SettlementMapper extends BaseMapper<Settlement> {

    /** 每人作为付款方的还钱总额（正贡献其应收净额） */
    @org.apache.ibatis.annotations.Select(
            "SELECT from_user AS userId, COALESCE(SUM(amount), 0) AS total "
                    + "FROM t_settlement WHERE ledger_id = #{ledgerId} GROUP BY from_user")
    List<java.util.Map<String, Object>> sumSentByUser(Long ledgerId);

    /** 每人作为收款方的进账总额（冲减其应收净额） */
    @org.apache.ibatis.annotations.Select(
            "SELECT to_user AS userId, COALESCE(SUM(amount), 0) AS total "
                    + "FROM t_settlement WHERE ledger_id = #{ledgerId} GROUP BY to_user")
    List<java.util.Map<String, Object>> sumReceivedByUser(Long ledgerId);
}
