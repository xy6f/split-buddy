package com.splitbuddy.module.expense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.expense.entity.ExpenseSplit;

/**
 * 分摊明细 Mapper。
 * 按账本聚合各人承担额（净额重算，计划书 10 风险）用注解 SQL 直接 SUM，
 * 避免把全表 split 拉进内存。
 */
public interface ExpenseSplitMapper extends BaseMapper<ExpenseSplit> {

    /**
     * 重算净额的数据源之一：某账本内每个用户承担的支出总额（分单位的精确 SUM 交给 Java 侧做
     * ——这里返回 DECIMAL，MyBatis 映射为 BigDecimal，无精度损失）。
     * JOIN t_expense 带上 deleted=0：逻辑删的支出其 split 已物理删，但双保险防历史脏数据。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT s.user_id AS userId, COALESCE(SUM(s.amount), 0) AS total "
                    + "FROM t_expense_split s JOIN t_expense e ON e.id = s.expense_id "
                    + "WHERE e.ledger_id = #{ledgerId} AND e.deleted = 0 "
                    + "GROUP BY s.user_id")
    java.util.List<java.util.Map<String, Object>> sumShareByUser(Long ledgerId);

    /** 重算净额的数据源之二：每人的垫付总额 */
    @org.apache.ibatis.annotations.Select(
            "SELECT paid_by AS userId, COALESCE(SUM(amount), 0) AS total "
                    + "FROM t_expense WHERE ledger_id = #{ledgerId} AND deleted = 0 GROUP BY paid_by")
    java.util.List<java.util.Map<String, Object>> sumPaidByUser(Long ledgerId);
}
