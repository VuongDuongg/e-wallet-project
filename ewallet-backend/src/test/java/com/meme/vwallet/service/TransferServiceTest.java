package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.exception.IdempotencyConflictException;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.WalletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private TransferTransactionService transferTransactionService;

    @InjectMocks
    private TransferService transferService;

    private TransferRequest request;
    private String idempotencyKey;
    private String redisKey;

    @BeforeEach
    void setUp() {
        request = TransferRequest.builder()
                .receiverPhone("0907654321")
                .amount(200_000L)
                .pin("123456")
                .description("Chuyen tien an trua")
                .build();

        idempotencyKey = "req-idemp-123";
        redisKey = "idemp:transfer:2:req-idemp-123";
    }

    @Test
    @DisplayName("Successful transfer checks receipt, acquires lock, verifies PIN outside TX, executes TX, and caches receipt")
    void transfer_success_verifiesPinOutsideTxAndCachesReceipt() {
        TransferResponse expected = TransferResponse.builder()
                .success(true)
                .transactionId(100L)
                .senderWalletId(10L)
                .receiverWalletId(20L)
                .amount(200_000L)
                .status(TransactionStatus.COMPLETED)
                .createdAt(Instant.now())
                .build();

        when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(transferTransactionService.executeTransferTx(2L, idempotencyKey, request)).thenReturn(expected);

        TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

        assertThat(response).isEqualTo(expected);

        InOrder order = inOrder(idempotencyService, userService, transferTransactionService);
        order.verify(idempotencyService).getReceipt(redisKey, TransferResponse.class);
        order.verify(idempotencyService).acquireLock(redisKey);
        order.verify(userService).verifyPin(2L, "123456");
        order.verify(transferTransactionService).executeTransferTx(2L, idempotencyKey, request);
        order.verify(idempotencyService).saveReceipt(redisKey, expected, Duration.ofHours(24));

        verify(idempotencyService, never()).releaseLock(any());
    }

    @Test
    @DisplayName("Cached Redis receipt returns immediately without acquiring lock, checking PIN, or opening DB transaction")
    void transfer_cachedReceipt_returnsImmediately() {
        TransferResponse cached = TransferResponse.builder()
                .success(true)
                .transactionId(999L)
                .senderWalletId(10L)
                .receiverWalletId(20L)
                .amount(200_000L)
                .status(TransactionStatus.COMPLETED)
                .createdAt(Instant.now())
                .build();

        when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.of(cached));

        TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

        assertThat(response).isEqualTo(cached);
        verify(idempotencyService, never()).acquireLock(any());
        verify(userService, never()).verifyPin(any(), any());
        verify(transferTransactionService, never()).executeTransferTx(any(), any(), any());
    }

    @Test
    @DisplayName("Lock conflict throws IdempotencyConflictException without verifying PIN or calling TransferTransactionService")
    void transfer_lockConflict_throwsIdempotencyConflictException() {
        when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(false);

        assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(userService, never()).verifyPin(any(), any());
        verify(transferTransactionService, never()).executeTransferTx(any(), any(), any());
        verify(idempotencyService, never()).releaseLock(any());
    }

    @Test
    @DisplayName("Input validation rejects null request, blank idempotencyKey, >64 char key, blank receiverPhone, and amount <= 0")
    void transfer_invalidInputs_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> transferService.transfer(null, idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferService.transfer(2L, "   ", request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferService.transfer(2L, "x".repeat(65), request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, null))
                .isInstanceOf(IllegalArgumentException.class);

        request.setAmount(0L);
        assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class);

        request.setAmount(200_000L);
        request.setReceiverPhone("   ");
        assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(idempotencyService, never()).acquireLock(any());
        verify(userService, never()).verifyPin(any(), any());
        verify(transferTransactionService, never()).executeTransferTx(any(), any(), any());
    }

    @Test
    @DisplayName("Invalid PIN releases lock and never starts DB transaction")
    void transfer_invalidPin_releasesLockAndSkipsTransaction() {
        when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        doThrow(new InvalidPinException("Invalid PIN")).when(userService).verifyPin(2L, "123456");

        assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                .isInstanceOf(InvalidPinException.class);

        verify(transferTransactionService, never()).executeTransferTx(any(), any(), any());
        verify(idempotencyService).releaseLock(redisKey);
        verify(idempotencyService, never()).saveReceipt(any(), any(), any());
    }

    @Test
    @DisplayName("safeReleaseLock swallows Redis exception so original business exception is preserved")
    void transfer_releaseLockFails_preservesOriginalBusinessException() {
        when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .thenThrow(new WalletException("Ví người gửi không ở trạng thái hoạt động"));
        doThrow(new RuntimeException("Redis connection lost")).when(idempotencyService).releaseLock(redisKey);

        assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class)
                .hasMessageContaining("Ví người gửi không ở trạng thái hoạt động");

        verify(idempotencyService).releaseLock(redisKey);
    }

    @Test
    @DisplayName("Post-commit Redis saveReceipt failure does not release lock and returns committed TransferResponse")
    void transfer_saveReceiptFailsAfterCommit_doesNotReleaseLockAndReturnsResponse() {
        TransferResponse committed = TransferResponse.builder()
                .success(true)
                .transactionId(777L)
                .senderWalletId(10L)
                .receiverWalletId(20L)
                .amount(200_000L)
                .status(TransactionStatus.COMPLETED)
                .createdAt(Instant.now())
                .build();

        when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(transferTransactionService.executeTransferTx(2L, idempotencyKey, request)).thenReturn(committed);
        doThrow(new RuntimeException("Redis down"))
                .when(idempotencyService).saveReceipt(eq(redisKey), eq(committed), eq(Duration.ofHours(24)));

        TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

        assertThat(response).isEqualTo(committed);
        verify(idempotencyService).saveReceipt(redisKey, committed, Duration.ofHours(24));
        verify(idempotencyService, never()).releaseLock(any());
    }
}
