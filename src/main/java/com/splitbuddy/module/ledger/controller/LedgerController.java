package com.splitbuddy.module.ledger.controller;

import com.splitbuddy.common.Result;
import com.splitbuddy.module.ledger.dto.LedgerDto;
import com.splitbuddy.module.ledger.service.LedgerService;
import com.splitbuddy.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账本接口（计划书第 7 章 #3 #4 #5）。
 *
 * <p>当前用户ID 一律从令牌解析（CurrentUser.userId()），
 * 绝不信任请求体里传的 userId——"身份从凭证来，不从参数来"是接口安全基本功。</p>
 */
@RestController
@RequestMapping("/api/ledgers")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @PostMapping
    public Result<LedgerDto.LedgerVO> create(@Valid @RequestBody LedgerDto.CreateReq req) {
        return Result.ok(ledgerService.create(CurrentUser.userId(), req));
    }

    @GetMapping
    public Result<List<LedgerDto.LedgerVO>> my() {
        return Result.ok(ledgerService.myLedgers(CurrentUser.userId()));
    }

    @PostMapping("/{ledgerId}/members")
    public Result<Void> invite(@PathVariable Long ledgerId,
                               @Valid @RequestBody LedgerDto.InviteReq req) {
        ledgerService.invite(ledgerId, CurrentUser.userId(), req.username());
        return Result.ok();
    }

    /** 成员名单：userId/nickname/joinedAt，权限（assertMember）在 Service 层第一行强制 */
    @GetMapping("/{ledgerId}/members")
    public Result<List<LedgerDto.MemberVO>> members(@PathVariable Long ledgerId) {
        return Result.ok(ledgerService.members(ledgerId, CurrentUser.userId()));
    }
}
