package com.splitbuddy.module.expense.controller;

import com.splitbuddy.common.Result;
import com.splitbuddy.module.expense.dto.ExpenseDto;
import com.splitbuddy.module.expense.service.ExpenseService;
import com.splitbuddy.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支出接口（计划书第 7 章 #6~#9）。
 *
 * <p>与 LedgerController 同风格：Controller 薄如纸——绑定、@Valid 触发、
 * 从令牌取当前用户、转发 Service。成员/改删权限都在 Service 第一行，
 * 这里只负责把 HTTP 参数形态转成调用参数。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    /** #6 记一笔（多表事务 + 净额增量 + 分摊校验，见 8.1） */
    @PostMapping("/ledgers/{ledgerId}/expenses")
    public Result<ExpenseDto.ExpenseVO> create(@PathVariable Long ledgerId,
                                               @Valid @RequestBody ExpenseDto.CreateReq req) {
        return Result.ok(expenseService.create(ledgerId, CurrentUser.userId(), req));
    }

    /** #7 改一笔：请求体带 version，冲突返回 HTTP 409（BizException.conflict 经全局处理器翻译） */
    @PutMapping("/expenses/{expenseId}")
    public Result<Void> update(@PathVariable Long expenseId,
                               @Valid @RequestBody ExpenseDto.UpdateReq req) {
        expenseService.update(expenseId, CurrentUser.userId(), req);
        return Result.ok();
    }

    /** #8 删一笔：version 走查询参数（DELETE 带体不通用），冲突同样 409 */
    @DeleteMapping("/expenses/{expenseId}")
    public Result<Void> delete(@PathVariable Long expenseId, @RequestParam Integer version) {
        expenseService.delete(expenseId, CurrentUser.userId(), version);
        return Result.ok();
    }

    /** #9 账本明细分页：page 从 1 起，size 上限由 Service 钳制 */
    @GetMapping("/ledgers/{ledgerId}/expenses")
    public Result<ExpenseDto.PageVO<ExpenseDto.ExpenseVO>> page(
            @PathVariable Long ledgerId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(expenseService.page(ledgerId, CurrentUser.userId(), page, size));
    }
}
