package com.splitbuddy.module.expense.entity;

/**
 * 分摊方式枚举（计划书 FR-03 的三种方式）。
 *
 * <p>取舍：库里存字符串而非数字码——审计日志和排障时肉眼可读比省 2 字节重要；
 * 校验时未知字符串直接归为参数错误。</p>
 */
public enum ShareType {

    /** 均摊：所有参与人平分，除不尽的余数（分级）归付款人承担 */
    EQUAL,

    /** 按份额：share 是权重（如 1:2:3），按比例分摊总额 */
    RATIO,

    /** 指定金额：share 即该人应承担的具体金额，要求各行之和恰为总额 */
    AMOUNT
}
