package com.meme.vwallet.service;

import com.meme.vwallet.service.ledger.LedgerService;
import com.meme.vwallet.service.transfer.TransferTransactionService;
import com.meme.vwallet.service.user.UserService;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.event.TransactionCompletedEvent;
import com.meme.vwallet.exception.IdempotencyConflictException;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
class TransferTransactionServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserService userService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TransferTransactionService transferTransactionService;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;
    private TransferRequest request;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        senderUser = User.builder()
                .id(2L)
                .phoneNumber("0901234567")
                .fullName("Sender User")
                .status("ACTIVE")
                .build();

        receiverUser = User.builder()
                .id(3L)
                .phoneNumber("0907654321")
                .fullName("Receiver User")
                .status("ACTIVE")
                .build();

        senderWallet = Wallet.builder()
                .id(10L)
                .user(senderUser)
                .balance(1_000_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        receiverWallet = Wallet.builder()
                .id(20L)
                .user(receiverUser)
                .balance(500_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        request = TransferRequest.builder()
                .receiverPhone("0907654321")
                .amount(200_000L)
                .pin("123456")
                .description("Chuyen tien an trua")
                .build();

        idempotencyKey = "req-idemp-123";
    }

    @Test
    @DisplayName("Successful TX uses scalar wallet ID lookup, min->max FOR UPDATE lock, double-entry, and synced createdAt")
    void executeTransferTx_success() {
        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

        Instant now = Instant.now();
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            tx.setId(100L);
            tx.setCreatedAt(now);
            return tx;
        });

        TransferResponse response = transferTransactionService.executeTransferTx(2L, idempotencyKey, request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getTransactionId()).isEqualTo(100L);
        assertThat(response.getSenderWalletId()).isEqualTo(10L);
        assertThat(response.getReceiverWalletId()).isEqualTo(20L);
        assertThat(response.getAmount()).isEqualTo(200_000L);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.getCreatedAt()).isEqualTo(now);

        verify(walletRepository).findIdByUserId(2L);
        verify(walletRepository).findIdByUserId(3L);
        verify(walletRepository, never()).findByUserId(any());

        InOrder lockOrder = inOrder(walletRepository);
        lockOrder.verify(walletRepository).findByIdForUpdate(10L);
        lockOrder.verify(walletRepository).findByIdForUpdate(20L);

        verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

        ArgumentCaptor<TransactionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getOccurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Inverted wallet IDs lock min(receiverWalletId) then max(senderWalletId) and sync fallback createdAt")
    void executeTransferTx_invertedIds_locksAscendingOrder() {
        senderWallet.setId(50L);
        receiverWallet.setId(15L);

        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(50L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(15L));
        when(walletRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(receiverWallet));
        when(walletRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(senderWallet));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            tx.setId(101L);
            return tx;
        });

        TransferResponse response = transferTransactionService.executeTransferTx(2L, idempotencyKey, request);

        InOrder lockOrder = inOrder(walletRepository);
        lockOrder.verify(walletRepository).findByIdForUpdate(15L);
        lockOrder.verify(walletRepository).findByIdForUpdate(50L);

        ArgumentCaptor<TransactionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(response.getCreatedAt()).isEqualTo(eventCaptor.getValue().getOccurredAt());
    }

    @Test
    @DisplayName("DB idempotency fallback returns existing COMPLETED transaction for same sender when Redis cache misses")
    void executeTransferTx_dbFallbackSameSender_returnsExistingResponse() {
        Instant existingTime = Instant.now().minusSeconds(3600);
        Transaction existingTx = Transaction.builder()
                .id(888L)
                .idempotencyKey(idempotencyKey)
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(200_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .createdAt(existingTime)
                .build();

        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTx));

        TransferResponse response = transferTransactionService.executeTransferTx(2L, idempotencyKey, request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getTransactionId()).isEqualTo(888L);
        assertThat(response.getSenderWalletId()).isEqualTo(10L);
        assertThat(response.getReceiverWalletId()).isEqualTo(20L);
        assertThat(response.getCreatedAt()).isEqualTo(existingTime);

        verify(walletRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
        verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("DB idempotency fallback rejects key belonging to another sender with IdempotencyConflictException")
    void executeTransferTx_dbFallbackDifferentSender_throwsIdempotencyConflict() {
        Transaction otherTx = Transaction.builder()
                .id(999L)
                .idempotencyKey(idempotencyKey)
                .senderWallet(receiverWallet)
                .receiverWallet(senderWallet)
                .amount(200_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .build();

        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(otherTx));

        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(walletRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("TOCTOU check: throws WalletException when lockedSender becomes non-ACTIVE after FOR UPDATE")
    void executeTransferTx_lockedSenderNonActiveAfterLock_throwsWalletException() {
        senderWallet.setStatus(WalletStatus.LOCKED);
        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class)
                .hasMessageContaining("Ví người gửi không ở trạng thái hoạt động");

        verify(transactionRepository, never()).save(any());
        verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("TOCTOU check: throws WalletException when lockedReceiver becomes CLOSED after FOR UPDATE")
    void executeTransferTx_lockedReceiverNonActiveAfterLock_throwsWalletException() {
        receiverWallet.setStatus(WalletStatus.CLOSED);
        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class)
                .hasMessageContaining("Ví người nhận không ở trạng thái hoạt động");

        verify(transactionRepository, never()).save(any());
        verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Locked sender user throws UserLockedException; locked receiver user throws WalletException")
    void executeTransferTx_userStatusGuards() {
        senderUser.setStatus("LOCKED");
        when(userService.findById(2L)).thenReturn(senderUser);
        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(UserLockedException.class);

        senderUser.setStatus("ACTIVE");
        receiverUser.setStatus("LOCKED");
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);

        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class);
        verify(walletRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Self-transfer and system account transfer are rejected before pessimistic locking")
    void executeTransferTx_selfTransferAndSystemGuards() {
        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(10L));

        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class)
                .hasMessageContaining("Không thể tự chuyển tiền cho chính mình");

        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(1L));
        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class);

        verify(walletRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Insufficient balance propagates InsufficientBalanceException")
    void executeTransferTx_insufficientBalance_throwsException() {
        when(userService.findById(2L)).thenReturn(senderUser);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
        when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

        Transaction savedTx = Transaction.builder()
                .id(100L)
                .idempotencyKey(idempotencyKey)
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(200_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .build();
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);
        doThrow(new InsufficientBalanceException("Số dư không đủ"))
                .when(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

        assertThatThrownBy(() -> transferTransactionService.executeTransferTx(2L, idempotencyKey, request))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
