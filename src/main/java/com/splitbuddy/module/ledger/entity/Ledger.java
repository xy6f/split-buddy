package com.splitbuddy.module.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 账本实体（t_ledger）：一个账本=一群人的一段共同消费（如"毕业旅行"） */
@Data
@TableName("t_ledger")
public class Ledger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 创建者；owner 权限略高（可邀请成员/删账本），见 LedgerService.assertMember */
    private Long ownerId;

    private String currency;

    private LocalDateTime createdAt;
}
