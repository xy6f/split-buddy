package com.splitbuddy.module.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 账本相关 DTO 容器（与 AuthDto 同风格，record 集中管理） */
public final class LedgerDto {

    private LedgerDto() {}

    /** 创建账本请求 */
    public record CreateReq(
            @NotBlank @Size(max = 64) String name) {}

    /** 邀请成员请求：按用户名精确邀请（简化：不做邀请链接/确认流） */
    public record InviteReq(
            @NotBlank String username) {}

    /**
     * 账本视图对象：VO 与实体分离，实体字段演进不破坏接口契约。
     * myNet 对齐计划书 7 章 #5 出参约定 [{id,name,myNet}]——
     * 列表页一眼看到"我还欠/被欠多少"，避免进详情再查净额表。
     * 新建账本 myNet 恒为 0；无净额行同样返回 0。
     */
    public record LedgerVO(Long id, String name, Long ownerId, String currency, BigDecimal myNet) {}

    /**
     * 账本成员视图对象：join t_ledger_member + t_user 一次取齐，
     * 前端成员页/资金流向图不再需要从净额记录"倒推"名单
     * （历史降级逻辑：没有成员接口时只能用有净额行的人近似当成员，漏掉零净额成员）。
     */
    public record MemberVO(Long userId, String nickname, LocalDateTime joinedAt) {}
}
