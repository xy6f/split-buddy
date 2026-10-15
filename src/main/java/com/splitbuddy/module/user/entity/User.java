package com.splitbuddy.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体（t_user）。
 *
 * <p>passwordHash 用 @TableField(select=false)：默认查询永不带出散列值，
 * 防止意外序列化泄露——这是"字段级防呆"的小设计（面试可提）。</p>
 */
@Data
@TableName("t_user")
public class User {

    /** AUTO 对应数据库自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 散列；只写不读 */
    @TableField(select = false)
    private String passwordHash;

    private String nickname;

    private LocalDateTime createdAt;
}
