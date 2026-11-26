package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.IdempotencyConflictException;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserService userService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private TransferService transferService;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;
    private TransferRequest request;
    private String idempotencyKey;
    private String redisKey;

    @BeforeEach
    void setUp() {
        senderUser = User.builder()
                .id(1L)
                .phoneNumber("0901234567")
                .fullName("Sender User")
                .build();

        receiverUser = User.builder()
                .id(2L)
                .phoneNumber("0907654321")
                .fullName("Receiver User")
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
        redisKey = "idemp:transfer:1:req-idemp-123";
    }

    @Nested
    @DisplayName("transfer - happy path and caching")
    class HappyPathAndCachingTests {

        @Test
        @DisplayName("Test 1: Successful transfer debits sender, credits receiver, creates 2 ledger entries, records completed transaction, caches response in Redis")
        void transfer_success() {
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

            when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

            Instant now = Instant.now();
            Transaction savedTx = Transaction.builder()
                    .id(100L)
                    .idempotencyKey(idempotencyKey)
                    .senderWallet(senderWallet)
                    .receiverWallet(receiverWallet)
                    .amount(200_000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .description("Chuyen tien an trua")
                    .createdAt(now)
                    .build();
            when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

            TransferResponse response = transferService.transfer(1L, idempotencyKey, request);

            assertThat(response).isNotNull();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getTransactionId()).isEqualTo(100L);
            assertThat(response.getSenderWalletId()).isEqualTo(10L);
            assertThat(response.getReceiverWalletId()).isEqualTo(20L);
            assertThat(response.getAmount()).isEqualTo(200_000L);
            assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.getCreatedAt()).isEqualTo(now);

            // Verify PIN was verified
            verify(userService).verifyPin(1L, "123456");

            // Verify lock acquisition order min(10, 20) -> max(10, 20)
            InOrder inOrder = inOrder(walletRepository);
            inOrder.verify(walletRepository).findByIdForUpdate(10L);
            inOrder.verify(walletRepository).findByIdForUpdate(20L);

            // Verify ledger double entry called
            verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

            // Verify receipt saved with 24 hours TTL
            verify(idempotencyService).saveReceipt(eq(redisKey), any(TransferResponse.class), eq(Duration.ofHours(24)));

            // Verify lock was NOT released (it stays as receipt)
            verify(idempotencyService, never()).releaseLock(any());
        }

        @Test
        @DisplayName("Test 1b: Successful transfer with inverted IDs locks min(receiverId) then max(senderId)")
        void transfer_success_invertedIds() {
            senderWallet.setId(50L);
            receiverWallet.setId(15L);

            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

            when(walletRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(receiverWallet));
            when(walletRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(senderWallet));

            Transaction savedTx = Transaction.builder()
                    .id(101L)
                    .idempotencyKey(idempotencyKey)
                    .senderWallet(senderWallet)
                    .receiverWallet(receiverWallet)
                    .amount(200_000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .build();
            when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

            TransferResponse response = transferService.transfer(1L, idempotencyKey, request);

            assertThat(response).isNotNull();
            assertThat(response.isSuccess()).isTrue();

            // Verify lock acquisition order was min(15) then max(50)
            InOrder inOrder = inOrder(walletRepository);
            inOrder.verify(walletRepository).findByIdForUpdate(15L);
            inOrder.verify(walletRepository).findByIdForUpdate(50L);

            // Double entry still has sender=senderWallet, receiver=receiverWallet
            verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));
        }

        @Test
        @DisplayName("Test 5: Existing COMPLETED idempotency key directly returns cached receipt without hitting DB")
        void transfer_cachedReceipt_returnsImmediatelyWithoutDbHit() {
            TransferResponse cachedResponse = TransferResponse.builder()
                    .success(true)
                    .transactionId(999L)
                    .senderWalletId(10L)
                    .receiverWalletId(20L)
                    .amount(200_000L)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(Instant.now())
                    .build();

            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.of(cachedResponse));

            TransferResponse response = transferService.transfer(1L, idempotencyKey, request);

            assertThat(response).isEqualTo(cachedResponse);

            verify(idempotencyService, never()).acquireLock(any());
            verify(userService, never()).findById(any());
            verify(walletRepository, never()).findByUserId(any());
            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Idempotency conflict throws IdempotencyConflictException when lock cannot be acquired")
        void transfer_lockConflict_throwsIdempotencyConflictException() {
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(false);

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(userService, never()).findById(any());
            verify(walletRepository, never()).findByIdForUpdate(any());
        }
    }

    @Nested
    @DisplayName("transfer - validation and error handling")
    class ValidationAndErrorHandlingTests {

        @Test
        @DisplayName("Test 2: Self-transfer attempt (senderWallet.getId().equals(receiverWallet.getId())) throws WalletException before lock acquisition and releases lock")
        void transfer_selfTransfer_throwsWalletExceptionAndReleasesLock() {
            receiverWallet.setId(10L); // Same wallet id as sender

            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Không thể tự chuyển tiền cho chính mình");

            // Verify no pessimistic lock was acquired
            verify(walletRepository, never()).findByIdForUpdate(any());
            // Verify PIN was not verified
            verify(userService, never()).verifyPin(any(), any());
            // Verify idempotency lock was released
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("Test 3: Invalid PIN throws InvalidPinException and releases idempotency lock")
        void transfer_invalidPin_throwsInvalidPinExceptionAndReleasesLock() {
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

            doThrow(new InvalidPinException("Invalid PIN")).when(userService).verifyPin(1L, "123456");

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(InvalidPinException.class)
                    .hasMessageContaining("Invalid PIN");

            // Verify no pessimistic lock was acquired
            verify(walletRepository, never()).findByIdForUpdate(any());
            // Verify idempotency lock was released
            verify(idempotencyService).releaseLock(redisKey);
            // Verify no receipt saved
            verify(idempotencyService, never()).saveReceipt(any(), any(), any());
        }

        @Test
        @DisplayName("Test 4: Insufficient balance throws InsufficientBalanceException and releases idempotency lock")
        void transfer_insufficientBalance_throwsInsufficientBalanceExceptionAndReleasesLock() {
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

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

            doThrow(new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch"))
                    .when(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("Số dư không đủ");

            // Verify idempotency lock was released
            verify(idempotencyService).releaseLock(redisKey);
            // Verify no receipt saved
            verify(idempotencyService, never()).saveReceipt(any(), any(), any());
        }

        @Test
        @DisplayName("should throw WalletException when sender wallet is not active")
        void shouldThrowWhenSenderWalletNotActive() {
            senderWallet.setStatus(WalletStatus.LOCKED);
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber(request.getReceiverPhone())).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Ví người gửi không ở trạng thái hoạt động");

            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("should throw WalletException when receiver wallet is not active")
        void shouldThrowWhenReceiverWalletNotActive() {
            receiverWallet.setStatus(WalletStatus.CLOSED);
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(1L)).thenReturn(senderUser);
            when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(senderWallet));
            when(userService.findByPhoneNumber(request.getReceiverPhone())).thenReturn(receiverUser);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(receiverWallet));

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Ví người nhận không ở trạng thái hoạt động");

            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("should throw WalletException when attempting P2P transfer to SYSTEM_USER (id=1L)")
        void transfer_toSystemUserId1_throwsWalletException() {
            User actualSender = User.builder()
                    .id(2L)
                    .phoneNumber("0901234567")
                    .fullName("Sender User")
                    .build();
            Wallet actualSenderWallet = Wallet.builder()
                    .id(10L)
                    .user(actualSender)
                    .balance(1_000_000L)
                    .currency("VND")
                    .status(WalletStatus.ACTIVE)
                    .build();
            User systemUser = User.builder()
                    .id(1L)
                    .phoneNumber("0000000000")
                    .fullName("SYSTEM_USER")
                    .build();
            TransferRequest systemRequest = TransferRequest.builder()
                    .receiverPhone("0000000000")
                    .amount(200_000L)
                    .pin("123456")
                    .description("To system")
                    .build();
            String senderRedisKey = "idemp:transfer:2:" + idempotencyKey;

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(senderRedisKey);
            when(idempotencyService.getReceipt(senderRedisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(senderRedisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(actualSender);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(actualSenderWallet));
            when(userService.findByPhoneNumber("0000000000")).thenReturn(systemUser);

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, systemRequest))
                    .isInstanceOf(WalletException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(idempotencyService).releaseLock(senderRedisKey);
        }

        @Test
        @DisplayName("should throw WalletException when receiver wallet id is system wallet (id=1L)")
        void transfer_toSystemWalletId1_throwsWalletException() {
            User actualSender = User.builder()
                    .id(2L)
                    .phoneNumber("0901234567")
                    .fullName("Sender User")
                    .build();
            Wallet actualSenderWallet = Wallet.builder()
                    .id(10L)
                    .user(actualSender)
                    .balance(1_000_000L)
                    .currency("VND")
                    .status(WalletStatus.ACTIVE)
                    .build();
            User targetUser = User.builder()
                    .id(3L)
                    .phoneNumber("0907654321")
                    .fullName("Target User")
                    .build();
            Wallet systemWallet = Wallet.builder()
                    .id(1L)
                    .user(targetUser)
                    .balance(1_000_000L)
                    .currency("VND")
                    .status(WalletStatus.ACTIVE)
                    .build();
            String senderRedisKey = "idemp:transfer:2:" + idempotencyKey;

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(senderRedisKey);
            when(idempotencyService.getReceipt(senderRedisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(senderRedisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(actualSender);
            when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(actualSenderWallet));
            when(userService.findByPhoneNumber(request.getReceiverPhone())).thenReturn(targetUser);
            when(walletRepository.findByUserId(3L)).thenReturn(Optional.of(systemWallet));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(idempotencyService).releaseLock(senderRedisKey);
        }
    }
}
