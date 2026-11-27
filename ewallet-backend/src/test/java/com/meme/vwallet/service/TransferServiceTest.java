package com.meme.vwallet.service;

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
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
class TransferServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TransferService transferService;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;
    private TransferRequest request;
    private String idempotencyKey;
    private String redisKey;
    private String hashedDbKey;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

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
        redisKey = "idemp:transfer:2:req-idemp-123";
        hashedDbKey = DigestUtils.sha256Hex("P2P:2:" + idempotencyKey);
    }

    @Nested
    @DisplayName("transfer - happy path and caching")
    class HappyPathAndCachingTests {

        @Test
        @DisplayName("Test 1: Successful transfer uses scalar wallet ID lookup, hashes idempotency key, locks min->max, debits/credits, and caches receipt")
        void transfer_success() {
            String longKey = "req-" + "x".repeat(120);
            String longRedisKey = "idemp:transfer:2:" + longKey;
            String expectedHashedKey = DigestUtils.sha256Hex("P2P:2:" + longKey);

            when(idempotencyService.formatTransferKey(2L, longKey)).thenReturn(longRedisKey);
            when(idempotencyService.getReceipt(longRedisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(longRedisKey)).thenReturn(true);

            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(expectedHashedKey)).thenReturn(Optional.empty());
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

            TransferResponse response = transferService.transfer(2L, longKey, request);

            assertThat(response).isNotNull();
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

            verify(userService).verifyPin(2L, "123456");

            InOrder inOrder = inOrder(walletRepository);
            inOrder.verify(walletRepository).findByIdForUpdate(10L);
            inOrder.verify(walletRepository).findByIdForUpdate(20L);

            ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getIdempotencyKey()).hasSize(64);
            assertThat(txCaptor.getValue().getIdempotencyKey()).isEqualTo(expectedHashedKey);

            verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

            ArgumentCaptor<TransactionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCompletedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            TransactionCompletedEvent event = eventCaptor.getValue();
            assertThat(event.getEventId()).isNotBlank();
            assertThat(event.getTransactionId()).isEqualTo(100L);
            assertThat(event.getTransactionType()).isEqualTo(TransactionType.P2P_TRANSFER);
            assertThat(event.getSenderUserId()).isEqualTo(2L);
            assertThat(event.getSenderPhone()).isEqualTo("0901234567");
            assertThat(event.getSenderName()).isEqualTo("Sender User");
            assertThat(event.getReceiverUserId()).isEqualTo(3L);
            assertThat(event.getReceiverPhone()).isEqualTo("0907654321");
            assertThat(event.getReceiverName()).isEqualTo("Receiver User");
            assertThat(event.getAmount()).isEqualTo(200_000L);
            assertThat(event.getDescription()).isEqualTo("Chuyen tien an trua");
            assertThat(event.getOccurredAt()).isEqualTo(now);

            verify(idempotencyService).saveReceipt(eq(longRedisKey), any(TransferResponse.class), eq(Duration.ofHours(24)));
            verify(idempotencyService, never()).releaseLock(any());
        }

        @Test
        @DisplayName("Test 1b: Successful transfer with inverted IDs locks min(receiverId) then max(senderId)")
        void transfer_success_invertedIds() {
            senderWallet.setId(50L);
            receiverWallet.setId(15L);

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(50L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(15L));

            when(walletRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(receiverWallet));
            when(walletRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(senderWallet));

            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
                Transaction tx = inv.getArgument(0);
                tx.setId(101L);
                return tx;
            });

            TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

            assertThat(response).isNotNull();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getCreatedAt()).isNotNull();

            InOrder inOrder = inOrder(walletRepository);
            inOrder.verify(walletRepository).findByIdForUpdate(15L);
            inOrder.verify(walletRepository).findByIdForUpdate(50L);

            verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

            ArgumentCaptor<TransactionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCompletedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(response.getCreatedAt()).isEqualTo(eventCaptor.getValue().getOccurredAt());
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

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.of(cachedResponse));

            TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

            assertThat(response).isEqualTo(cachedResponse);

            verify(idempotencyService, never()).acquireLock(any());
            verify(transactionTemplate, never()).execute(any());
            verify(userService, never()).findById(any());
            verify(walletRepository, never()).findIdByUserId(any());
            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("DB idempotency fallback returns existing COMPLETED transaction when Redis receipt cache is missing")
        void transfer_dbFallback_returnsExistingTransactionWithoutDuplicateMutation() {
            Instant created = Instant.now().minusSeconds(3600);
            Transaction existingTx = Transaction.builder()
                    .id(888L)
                    .idempotencyKey(hashedDbKey)
                    .senderWallet(senderWallet)
                    .receiverWallet(receiverWallet)
                    .amount(200_000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(created)
                    .build();

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.of(existingTx));

            TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getTransactionId()).isEqualTo(888L);
            assertThat(response.getSenderWalletId()).isEqualTo(10L);
            assertThat(response.getReceiverWalletId()).isEqualTo(20L);
            assertThat(response.getAmount()).isEqualTo(200_000L);
            assertThat(response.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.getCreatedAt()).isEqualTo(created);

            verify(userService, never()).verifyPin(any(), any());
            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any());
            verify(idempotencyService).saveReceipt(eq(redisKey), eq(response), eq(Duration.ofHours(24)));
            verify(idempotencyService, never()).releaseLock(any());
        }

        @Test
        @DisplayName("Post-commit Redis saveReceipt failure does not release lock and returns committed TransferResponse")
        void transfer_saveReceiptFailsAfterCommit_doesNotReleaseLockAndReturnsResponse() {
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
            when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

            Instant now = Instant.now();
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
                Transaction tx = inv.getArgument(0);
                tx.setId(777L);
                tx.setCreatedAt(now);
                return tx;
            });

            doThrow(new RuntimeException("Redis down"))
                    .when(idempotencyService).saveReceipt(eq(redisKey), any(TransferResponse.class), eq(Duration.ofHours(24)));

            TransferResponse response = transferService.transfer(2L, idempotencyKey, request);

            assertThat(response).isNotNull();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getTransactionId()).isEqualTo(777L);
            verify(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));
            verify(idempotencyService, never()).releaseLock(any());
        }

        @Test
        @DisplayName("Idempotency conflict throws IdempotencyConflictException when lock cannot be acquired")
        void transfer_lockConflict_throwsIdempotencyConflictException() {
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(false);

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(transactionTemplate, never()).execute(any());
            verify(userService, never()).findById(any());
            verify(walletRepository, never()).findByIdForUpdate(any());
        }
    }

    @Nested
    @DisplayName("transfer - validation and security hardening")
    class ValidationAndErrorHandlingTests {

        @Test
        @DisplayName("Blank idempotencyKey, null request, or non-positive amount throws IllegalArgumentException before touching Redis or DB")
        void transfer_invalidInputs_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> transferService.transfer(2L, "   ", request))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, null))
                    .isInstanceOf(IllegalArgumentException.class);

            request.setAmount(0L);
            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(idempotencyService, never()).acquireLock(any());
            verify(transactionTemplate, never()).execute(any());
        }

        @Test
        @DisplayName("DB idempotency fallback rejects mismatched sender wallet with IdempotencyConflictException")
        void transfer_dbFallbackDifferentSender_throwsIdempotencyConflict() {
            Transaction otherTx = Transaction.builder()
                    .id(999L)
                    .idempotencyKey(hashedDbKey)
                    .senderWallet(receiverWallet)
                    .receiverWallet(senderWallet)
                    .amount(200_000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .build();

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.of(otherTx));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(IdempotencyConflictException.class);

            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("Locked sender user throws UserLockedException and releases lock")
        void transfer_lockedSenderUser_throwsUserLockedException() {
            senderUser.setStatus("LOCKED");
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(UserLockedException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("Locked receiver user throws WalletException (400, not 403 USER_LOCKED) and releases lock")
        void transfer_lockedReceiverUser_throwsWalletException() {
            receiverUser.setStatus("LOCKED");
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("Sender being system user or system wallet throws WalletException and releases lock")
        void transfer_fromSystemUserOrWallet_throwsWalletException() {
            senderUser.setId(1L);
            String sysRedisKey = "idemp:transfer:1:" + idempotencyKey;
            when(idempotencyService.formatTransferKey(1L, idempotencyKey)).thenReturn(sysRedisKey);
            when(idempotencyService.getReceipt(sysRedisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(sysRedisKey)).thenReturn(true);
            when(userService.findById(1L)).thenReturn(senderUser);

            assertThatThrownBy(() -> transferService.transfer(1L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(idempotencyService).releaseLock(sysRedisKey);
        }

        @Test
        @DisplayName("Test 2: Self-transfer attempt throws WalletException before lock acquisition and releases lock")
        void transfer_selfTransfer_throwsWalletExceptionAndReleasesLock() {
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(10L));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Không thể tự chuyển tiền cho chính mình");

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("Test 3: Invalid PIN throws InvalidPinException and releases idempotency lock")
        void transfer_invalidPin_throwsInvalidPinExceptionAndReleasesLock() {
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));

            doThrow(new InvalidPinException("Invalid PIN")).when(userService).verifyPin(2L, "123456");

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(InvalidPinException.class)
                    .hasMessageContaining("Invalid PIN");

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(idempotencyService).releaseLock(redisKey);
            verify(idempotencyService, never()).saveReceipt(any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Insufficient sender balance fails before creating Transaction record and releases lock")
        void transfer_insufficientBalancePreCheck_failsBeforeTransactionSave() {
            senderWallet.setBalance(50_000L);
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
            when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("Số dư không đủ");

            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("Test 4: LedgerService InsufficientBalanceException rolls back and releases idempotency lock")
        void transfer_insufficientBalance_throwsInsufficientBalanceExceptionAndReleasesLock() {
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);

            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0907654321")).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));

            when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

            Transaction savedTx = Transaction.builder()
                    .id(100L)
                    .idempotencyKey(hashedDbKey)
                    .senderWallet(senderWallet)
                    .receiverWallet(receiverWallet)
                    .amount(200_000L)
                    .type(TransactionType.P2P_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .build();
            when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

            doThrow(new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch"))
                    .when(ledgerService).executeDoubleEntry(any(Transaction.class), eq(senderWallet), eq(receiverWallet), eq(200_000L));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("Số dư không đủ");

            verify(idempotencyService).releaseLock(redisKey);
            verify(idempotencyService, never()).saveReceipt(any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("TOCTOU check: should throw WalletException using lockedSender status after FOR UPDATE")
        void shouldThrowWhenSenderWalletNotActiveAfterLock() {
            senderWallet.setStatus(WalletStatus.LOCKED);
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber(request.getReceiverPhone())).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
            when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Ví người gửi không ở trạng thái hoạt động");

            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("TOCTOU check: should throw WalletException using lockedReceiver status after FOR UPDATE")
        void shouldThrowWhenReceiverWalletNotActiveAfterLock() {
            receiverWallet.setStatus(WalletStatus.CLOSED);
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber(request.getReceiverPhone())).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(20L));
            when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(receiverWallet));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class)
                    .hasMessageContaining("Ví người nhận không ở trạng thái hoạt động");

            verify(transactionRepository, never()).save(any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("should throw WalletException when attempting P2P transfer to SYSTEM_USER (id=1L)")
        void transfer_toSystemUserId1_throwsWalletException() {
            User systemUser = User.builder()
                    .id(1L)
                    .phoneNumber("0000000000")
                    .fullName("SYSTEM_USER")
                    .status("ACTIVE")
                    .build();
            TransferRequest systemRequest = TransferRequest.builder()
                    .receiverPhone("0000000000")
                    .amount(200_000L)
                    .pin("123456")
                    .description("To system")
                    .build();

            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber("0000000000")).thenReturn(systemUser);

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, systemRequest))
                    .isInstanceOf(WalletException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }

        @Test
        @DisplayName("should throw WalletException when receiver wallet id is system wallet (id=1L)")
        void transfer_toSystemWalletId1_throwsWalletException() {
            when(idempotencyService.formatTransferKey(2L, idempotencyKey)).thenReturn(redisKey);
            when(idempotencyService.getReceipt(redisKey, TransferResponse.class)).thenReturn(Optional.empty());
            when(idempotencyService.acquireLock(redisKey)).thenReturn(true);
            when(userService.findById(2L)).thenReturn(senderUser);
            when(walletRepository.findIdByUserId(2L)).thenReturn(Optional.of(10L));
            when(transactionRepository.findByIdempotencyKey(hashedDbKey)).thenReturn(Optional.empty());
            when(userService.findByPhoneNumber(request.getReceiverPhone())).thenReturn(receiverUser);
            when(walletRepository.findIdByUserId(3L)).thenReturn(Optional.of(1L));

            assertThatThrownBy(() -> transferService.transfer(2L, idempotencyKey, request))
                    .isInstanceOf(WalletException.class);

            verify(walletRepository, never()).findByIdForUpdate(any());
            verify(userService, never()).verifyPin(any(), any());
            verify(ledgerService, never()).executeDoubleEntry(any(), any(), any(), any());
            verify(idempotencyService).releaseLock(redisKey);
        }
    }
}
