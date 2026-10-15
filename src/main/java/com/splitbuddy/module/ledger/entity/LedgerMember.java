package com.splitbuddy.module.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 账本成员关系（t_ledger_member）：uk(ledger_id,user_id) 防重复加入 */
@Data
@TableName("t_ledger_member")
public class LedgerMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ledgerId;

    private Long userId;

    private LocalDateTime joinedAt;
}
