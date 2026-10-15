package com.splitbuddy.module.expense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.splitbuddy.module.expense.entity.Expense;

/**
 * 支出 Mapper：单表 CRUD + 分页由 BaseMapper/Page 完成，
 * updateById 会被乐观锁插件自动织入 "WHERE version = ?"（计划书 5.3）。
 */
public interface ExpenseMapper extends BaseMapper<Expense> {
}
