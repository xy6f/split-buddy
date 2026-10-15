package com.splitbuddy.module.settlement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** 结清相关 DTO（接口 #11~#13，响应形态对齐计划书 7 章示例） */
public final class SettlementDto {

    private SettlementDto() {}

    /** 方案里的一条转账（nickname 冗余，方便前端直接画"A 转给 B"） */
    public record TransferVO(Long fromUser, String fromNickname,
                             Long toUser, String toNickname, BigDecimal amount) {}

    /**
     * 结清方案视图：{transfers, totalTransfers, algorithm}，
     * algorithm 标注算法链（greedy=贪心；greedy+subset=贪心+零和子集 DP），
     * 让接口响应自带"算法可解释性"，演示和面试都好用。
     */
    public record PlanVO(List<TransferVO> transfers, int totalTransfers, String algorithm) {}

    /** 手动登记还钱（#13） */
    public record ManualReq(
            @NotNull Long fromUser,
            @NotNull Long toUser,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount) {}

    /** 已落库的转账记录视图（#12 执行结果） */
    public record SettlementVO(Long id, Long fromUser, Long toUser,
                               BigDecimal amount, String source) {}
}
