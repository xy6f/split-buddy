package com.splitbuddy.module.balance.controller;

import com.splitbuddy.common.Result;
import com.splitbuddy.module.balance.dto.BalanceDto;
import com.splitbuddy.module.balance.service.BalanceService;
import com.splitbuddy.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 净额接口（#10 + 计划书 10 风险的内部对账能力）。
 */
@RestController
@RequestMapping("/api/ledgers")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;

    /** #10 净额表：[{userId,nickname,net}]，非成员 403 */
    @GetMapping("/{ledgerId}/balances")
    public Result<List<BalanceDto.BalanceVO>> balances(@PathVariable Long ledgerId) {
        return Result.ok(balanceService.listBalances(ledgerId, CurrentUser.userId()));
    }

    /**
     * 内部对账接口：全量重算该账本净额并返回前后求和。
     * 保留动机见计划书 10——删改导致净额漂移时，这是止血 + 定位的运维入口。
     */
    @PostMapping("/{ledgerId}/balances/recalculate")
    public Result<BalanceDto.ReconVO> recalculate(@PathVariable Long ledgerId) {
        return Result.ok(balanceService.recalculate(ledgerId, CurrentUser.userId()));
    }
}
