package com.splitbuddy.module.balance.dto;

import java.math.BigDecimal;
import java.util.List;

/** 净额视图 DTO（接口 #10） */
public final class BalanceDto {

    private BalanceDto() {}

    /** 单个成员净额：nickname 冗余返回，前端画"谁欠谁多少"不用再查用户 */
    public record BalanceVO(Long userId, String nickname, BigDecimal net) {}

    /**
     * 对账结果（计划书 10 风险保留的"账本净额重算"内部能力）：
     * 全量重算后写回 t_balance，并给出重算前后的净额和——不为 0 即数据漂移，需要报警排查。
     */
    public record ReconVO(BigDecimal sumBefore, BigDecimal sumAfter, int rowCount) {}
}
