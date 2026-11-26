package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.WithdrawalRequest;
import com.meme.vwallet.dto.response.WithdrawalResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.Withdrawal;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.event.TransactionCompletedEvent;
import com.meme.vwallet.exception.IdempotencyConflictException;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.repository.WithdrawalRepository;
import org.apache.commons.codec.digest.DigestUtils;
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
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WithdrawalRepository withdrawalRepository;

    @Mock
    private UserService userService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WithdrawalService withdrawalService;

    private User user;
    private User systemUser;
    private Wallet userWallet;
    private Wallet systemWallet;
    private WithdrawalRequest request;
    private String idempotencyKey;
    private String redisKey;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

        user = User.builder()
                .id(2L)
                .phoneNumber("0901234567")
                .fullName("Nguyen Van A")
                .status("ACTIVE")
                .build();

        systemUser = User.builder()
                .id(1L)
                .phoneNumber("0000000000")
                .fullName("SYSTEM_USER")
                .status("ACTIVE")
                .build();

        userWallet = Wallet.builder()
                .id(10L)
                .user(user)
                .balance(1_000_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        systemWallet = Wallet.builder()
                .id(1L)
                .user(systemUser)
                .balance(1_000_000_000_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .build();

        request = WithdrawalRequest.builder()
                .bankCode("VCB")
                .bankAccountNumber("1234567890")
                .accountHolderName("NGUYEN VAN A")
                .amount(200_000L)
                .pin("123456")
                .description("Rut tien ve tai khoan")
                .build();

        idempotencyKey = "wd-req-123";
        redisKey = "idemp:withdraw:2:wd-req-123";
    }

    @Test
    @DisplayName("withdraw - Success uses scalar wallet ID lookup, min->max lock, SHA-256 64-char key, double-entry, and flat event")
    void withdraw_success_executesDoubleEntryMinMaxLockAndHashesIdempotencyKey() {
        String longKey = "key-" + "x".repeat(120);
        String longRedisKey = "idemp:withdraw:2:" + longKey;
        String expectedHashedKey = DigestUtils.sha256Hex("WD:2:" + longKey);

        when(idempotencyService.formatWithdrawKey(2L, longKey)).thenReturn(longRedisKey);
        when(idempotencyService.getReceipt(longRedisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(longRedisKey)).thenReturn(true);

        when(userService.findById(2L)).thenReturn(user);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(systemWallet));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(userWallet));

        Instant now = Instant.now();
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            tx.setId(501L);
            tx.setCreatedAt(now);
            return tx;
        });

        doAnswer(inv -> {
            Wallet sender = inv.getArgument(1);
            Wallet receiver = inv.getArgument(2);
            Long amt = inv.getArgument(3);
            sender.setBalance(sender.getBalance() - amt);
            receiver.setBalance(receiver.getBalance() + amt);
            return null;
        }).when(ledgerService).executeDoubleEntry(any(Transaction.class), eq(userWallet), eq(systemWallet), eq(200_000L));

        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> {
            Withdrawal wd = inv.getArgument(0);
            wd.setId(77L);
            wd.setCreatedAt(now);
            return wd;
        });

        WithdrawalResponse response = withdrawalService.withdraw(2L, longKey, request);

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getWithdrawalId()).isEqualTo(77L);
        assertThat(response.getTransactionId()).isEqualTo(501L);
        assertThat(response.getBankCode()).isEqualTo("VCB");
        assertThat(response.getBankAccountNumber()).isEqualTo("1234567890");
        assertThat(response.getAccountHolderName()).isEqualTo("NGUYEN VAN A");
        assertThat(response.getAmount()).isEqualTo(200_000L);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.getBankRef()).startsWith("MOCK_BANK_");
        assertThat(response.getBalanceAfter()).isEqualTo(800_000L);
        assertThat(response.getCreatedAt()).isEqualTo(now);

        verify(walletRepository).findIdByUserId(2L);
        verify(walletRepository, never()).findByUserId(any());
        verify(userService).verifyPin(2L, "123456");

        InOrder lockOrder = inOrder(walletRepository);
        lockOrder.verify(walletRepository).findByIdForUpdate(1L);
        lockOrder.verify(walletRepository).findByIdForUpdate(10L);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction savedTx = txCaptor.getValue();
        assertThat(savedTx.getIdempotencyKey()).hasSize(64);
        assertThat(savedTx.getIdempotencyKey()).isEqualTo(expectedHashedKey);
        assertThat(savedTx.getType()).isEqualTo(TransactionType.WITHDRAW);
        assertThat(savedTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(savedTx.getSenderWallet()).isEqualTo(userWallet);
        assertThat(savedTx.getReceiverWallet()).isEqualTo(systemWallet);

        verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(userWallet), eq(systemWallet), eq(200_000L));

        ArgumentCaptor<Withdrawal> wdCaptor = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawalRepository).save(wdCaptor.capture());
        assertThat(wdCaptor.getValue().getBankCode()).isEqualTo("VCB");
        assertThat(wdCaptor.getValue().getAmount()).isEqualTo(200_000L);

        ArgumentCaptor<TransactionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        TransactionCompletedEvent event = eventCaptor.getValue();
        assertThat(event.getTransactionId()).isEqualTo(501L);
        assertThat(event.getTransactionType()).isEqualTo(TransactionType.WITHDRAW);
        assertThat(event.getSenderUserId()).isEqualTo(2L);
        assertThat(event.getSenderPhone()).isEqualTo("0901234567");
        assertThat(event.getSenderName()).isEqualTo("Nguyen Van A");
        assertThat(event.getAmount()).isEqualTo(200_000L);

        verify(idempotencyService).saveReceipt(eq(longRedisKey), any(WithdrawalResponse.class), eq(Duration.ofHours(24)));
        verify(idempotencyService, never()).releaseLock(any());
    }

    @Test
    @DisplayName("withdraw - Cached receipt returns immediately without acquiring lock or touching DB")
    void withdraw_cachedReceipt_returnsImmediatelyWithoutLocking() {
        WithdrawalResponse cached = WithdrawalResponse.builder()
                .success(true)
                .withdrawalId(55L)
                .transactionId(900L)
                .bankCode("VCB")
                .bankAccountNumber("1234567890")
                .accountHolderName("NGUYEN VAN A")
                .amount(200_000L)
                .status(TransactionStatus.COMPLETED)
                .bankRef("MOCK_BANK_12345678")
                .balanceAfter(800_000L)
                .createdAt(Instant.now())
                .build();

        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.of(cached));

        WithdrawalResponse result = withdrawalService.withdraw(2L, idempotencyKey, request);

        assertThat(result).isEqualTo(cached);
        verify(idempotencyService, never()).acquireLock(any());
        verify(transactionTemplate, never()).execute(any());
        verify(walletRepository, never()).findIdByUserId(any());
        verify(walletRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw - Concurrent lock conflict throws IdempotencyConflictException (409)")
    void withdraw_concurrentLock_throwsIdempotencyConflict409() {
        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(false);

        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(transactionTemplate, never()).execute(any());
        verify(idempotencyService, never()).releaseLock(any());
    }

    @Test
    @DisplayName("withdraw - Locked user throws UserLockedException (403) and releases idempotency lock")
    void withdraw_lockedUser_throwsUserLocked403AndReleasesLock() {
        user.setStatus("LOCKED");
        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(userService.findById(2L)).thenReturn(user);

        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(UserLockedException.class);

        verify(walletRepository, never()).findByIdForUpdate(any());
        verify(idempotencyService).releaseLock(redisKey);
        verify(idempotencyService, never()).saveReceipt(any(), any(), any());
    }

    @Test
    @DisplayName("withdraw - Wallet locked after scalar lookup throws WalletException after FOR UPDATE and releases lock")
    void withdraw_walletLockedAfterLookup_throwsWalletExceptionAndReleasesLock() {
        userWallet.setStatus(WalletStatus.LOCKED);
        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(userService.findById(2L)).thenReturn(user);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(systemWallet));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(userWallet));

        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(WalletException.class);

        verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
        verify(transactionRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(idempotencyService).releaseLock(redisKey);
    }

    @Test
    @DisplayName("withdraw - Wrong PIN throws InvalidPinException and releases idempotency lock")
    void withdraw_wrongPin_throwsInvalidPinAndReleasesLock() {
        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(userService.findById(2L)).thenReturn(user);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        doThrow(new InvalidPinException("Invalid PIN")).when(userService).verifyPin(2L, "123456");

        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(InvalidPinException.class);

        verify(walletRepository, never()).findByIdForUpdate(any());
        verify(idempotencyService).releaseLock(redisKey);
        verify(idempotencyService, never()).saveReceipt(any(), any(), any());
    }

    @Test
    @DisplayName("withdraw - Insufficient balance throws InsufficientBalanceException and releases idempotency lock")
    void withdraw_insufficientBalance_throwsInsufficientBalanceAndReleasesLock() {
        userWallet.setBalance(50_000L);
        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
        when(userService.findById(2L)).thenReturn(user);
        when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(systemWallet));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(userWallet));

        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
        verify(transactionRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
        verify(idempotencyService).releaseLock(redisKey);
    }

    @Test
    @DisplayName("withdraw - Amount below min or above max throws IllegalArgumentException and releases lock")
    void withdraw_belowMinOrAboveMaxAmount_throwsIllegalArgumentAndReleasesLock() {
        when(idempotencyService.formatWithdrawKey(2L, idempotencyKey)).thenReturn(redisKey);
        when(idempotencyService.getReceipt(redisKey, WithdrawalResponse.class)).thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

        request.setAmount(9_999L);
        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class);

        request.setAmount(50_000_001L);
        assertThatThrownBy(() -> withdrawalService.withdraw(2L, idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(transactionTemplate, never()).execute(any());
        verify(idempotencyService, org.mockito.Mockito.times(2)).releaseLock(redisKey);
    }
}
