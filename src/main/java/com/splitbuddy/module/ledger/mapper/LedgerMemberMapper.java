package com.splitbuddy.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.ledger.dto.LedgerDto;
import com.splitbuddy.module.ledger.entity.LedgerMember;

import java.util.List;

/**
 * 成员关系 Mapper。
 * selectMyLedgerIds：按 user_id 走 idx_user 索引，是"我的账本列表"的第一步查询。
 */
public interface LedgerMemberMapper extends BaseMapper<LedgerMember> {

    /**
     * 查某用户加入的全部账本ID（手写注解 SQL，简单 join 用不上 XML）。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT ledger_id FROM t_ledger_member WHERE user_id = #{userId}")
    List<Long> selectMyLedgerIds(Long userId);

    /**
     * 账本成员名单（带昵称）：join 成员表 + 用户表一次取齐，按加入时间排序。
     * 列名显式别名对齐 record 构造参数（user_id AS userId），不依赖隐式驼峰转换，
     * MyBatis 对无默认构造器的 record 走构造器自动映射，别名列顺序与参数一一对应最稳。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT m.user_id AS userId, u.nickname AS nickname, m.joined_at AS joinedAt "
                    + "FROM t_ledger_member m JOIN t_user u ON u.id = m.user_id "
                    + "WHERE m.ledger_id = #{ledgerId} ORDER BY m.joined_at, m.id")
    List<LedgerDto.MemberVO> selectLedgerMembers(Long ledgerId);
}
