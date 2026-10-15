package com.splitbuddy.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.user.entity.User;

/**
 * 用户表 Mapper：继承 BaseMapper 即得单表 CRUD，
 * 复杂查询（如统计）后续再加 XML/注解 SQL。
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 登录专用查询：显式带回 password_hash。
     *
     * <p>为什么通用方法不行（本文件存在的原因，冒烟实测踩出的 bug）：
     * 实体上 passwordHash 标了 @TableField(select=false)——通用 selectOne 永远
     * 取不到散列，登录比对必挂。"默认安全、按需取回"是正确姿势，
     * 但取回通道必须显式建模，这里就是那条通道。</p>
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT id, username, password_hash, nickname, created_at FROM t_user WHERE username = #{username}")
    User selectForLogin(String username);
}
