package com.splitbuddy.module.balance.service;

import com.splitbuddy.common.BizException;
import com.splitbuddy.module.balance.entity.Balance;
import com.splitbuddy.module.balance.mapper.BalanceMapper;
import com.splitbuddy.module.expense.mapper.ExpenseSplitMapper;
import com.splitbuddy.module.ledger.service.LedgerService;
import com.splitbuddy.module.settlement.mapper.SettlementMapper;
import com.splitbuddy.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 净额原子增量 applyDelta 单测——净额恒等式（SUM(net)=0）的写路径防呆：
 * 增量走数据库端 net=net+delta；无行时插首条；并发撞 UK 后重试一次。
 * 这四条分支任何一条松动都会破坏对账不变量，全部用 mock 锁死。
 */
@ExtendWith(MockitoExtension.class)
class BalanceServiceApplyDeltaTest {

    @Mock BalanceMapper balanceMapper;
    @Mock ExpenseSplitMapper splitMapper;
    @Mock SettlementMapper settlementMapper;
    @Mock UserMapper userMapper;
    @Mock LedgerService ledgerService;

    @InjectMocks BalanceService balanceService;

    @Test
    void zeroDeltaIsNoOp() {
        balanceService.applyDelta(1L, 2L, BigDecimal.ZERO);
        balanceService.applyDelta(1L, 2L, null);
        verifyNoInteractions(balanceMapper);
    }

    @Test
    void existingRowGetsAtomicUpdateWithoutInsert() {
        when(balanceMapper.addNet(1L, 2L, new BigDecimal("10.00"))).thenReturn(1);
        balanceService.applyDelta(1L, 2L, new BigDecimal("10.00"));
        verify(balanceMapper, never()).insert(any(Balance.class));
    }

    @Test
    void missingRowFallsBackToInsert() {
        when(balanceMapper.addNet(anyLong(), anyLong(), any())).thenReturn(0);
        balanceService.applyDelta(1L, 2L, new BigDecimal("-3.50"));
        verify(balanceMapper).insert(any(Balance.class));
    }

    @Test
    void concurrentInsertConflictRetriesAtomicUpdateOnce() {
        // 第一次 UPDATE 无行 -> 插行撞 UK（并发对手刚插）-> 重试 UPDATE 命中
        when(balanceMapper.addNet(anyLong(), anyLong(), any())).thenReturn(0, 1);
        when(balanceMapper.insert(any(Balance.class)))
                .thenThrow(new DuplicateKeyException("uk_ledger_user"));
        balanceService.applyDelta(1L, 2L, new BigDecimal("5.00"));
        // addNet 应被调用两次（首次 + 重试）
        verify(balanceMapper, org.mockito.Mockito.times(2)).addNet(anyLong(), anyLong(), any());
    }

    @Test
    void retryAlsoFailingRaisesBusinessError() {
        when(balanceMapper.addNet(anyLong(), anyLong(), any())).thenReturn(0, 0);
        when(balanceMapper.insert(any(Balance.class)))
                .thenThrow(new DuplicateKeyException("uk_ledger_user"));
        assertThrows(BizException.class,
                () -> balanceService.applyDelta(1L, 2L, new BigDecimal("5.00")));
    }
}
