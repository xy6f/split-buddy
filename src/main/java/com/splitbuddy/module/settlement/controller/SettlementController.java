package com.splitbuddy.module.settlement.controller;

import com.splitbuddy.common.Result;
import com.splitbuddy.module.settlement.dto.SettlementDto;
import com.splitbuddy.module.settlement.service.SettlementService;
import com.splitbuddy.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结清接口（#11 方案 / #12 执行 / #13 手动还钱）。
 * 响应体形态对齐计划书 7 章示例（transfers + totalTransfers + algorithm）。
 */
@RestController
@RequestMapping("/api/ledgers")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    /** #11 结清方案：只读、实时算，无副作用（取舍见 SettlementService 类注释） */
    @GetMapping("/{ledgerId}/settlement-plan")
    public Result<SettlementDto.PlanVO> plan(@PathVariable Long ledgerId) {
        return Result.ok(settlementService.plan(ledgerId, CurrentUser.userId()));
    }

    /** #12 一键执行：confirm 显式确认位，防止 GET 语义混淆或前端误触直接改账 */
    @PostMapping("/{ledgerId}/settlements/execute-plan")
    public Result<List<SettlementDto.SettlementVO>> execute(
            @PathVariable Long ledgerId,
            @RequestParam(defaultValue = "false") boolean confirm) {
        return Result.ok(settlementService.executePlan(ledgerId, CurrentUser.userId(), confirm));
    }

    /** #13 手动登记还钱（FR-07） */
    @PostMapping("/{ledgerId}/settlements")
    public Result<SettlementDto.SettlementVO> manual(@PathVariable Long ledgerId,
                                                     @Valid @RequestBody SettlementDto.ManualReq req) {
        return Result.ok(settlementService.manual(ledgerId, CurrentUser.userId(), req));
    }
}
