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
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final Duration RECEIPT_TTL = Duration.ofHours(24);

    private final TransactionTemplate transactionTemplate;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final UserService userService;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${vwallet.system.user-id:1}")
    private Long systemUserId = 1L;

    @Value("${vwallet.system.wallet-id:1}")
    private Long systemWalletId = 1L;

    /**
     * Outer non-transactional orchestration method.
     * Validates inputs, manages Redis idempotency lock/receipt caching outside the DB transaction boundary,
     * and isolates post-commit receipt caching failures from committed transfers.
     */
    public TransferResponse transfer(Long senderUserId, String idempotencyKey, TransferRequest request) {
        if (senderUserId == null) {
            throw new IllegalArgumentException("Thông tin người gửi không hợp lệ");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key không được để trống");
        }
        if (request == null || request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Yêu cầu chuyển tiền không hợp lệ");
        }

        String redisKey = idempotencyService.formatTransferKey(senderUserId, idempotencyKey);

        // 1. Check existing cached receipt in Redis
        Optional<TransferResponse> cachedReceipt = idempotencyService.getReceipt(redisKey, TransferResponse.class);
        if (cachedReceipt.isPresent()) {
            log.info("Returning cached transfer response for key: {}", redisKey);
            return cachedReceipt.get();
        }

        // 2. Acquire Redis distributed idempotency lock
        boolean locked = idempotencyService.acquireLock(redisKey);
        if (!locked) {
            log.warn("Idempotency conflict for key: {}", redisKey);
            throw new IdempotencyConflictException("Giao dịch đang được xử lý, vui lòng không gửi lại yêu cầu");
        }

        // 3. Execute DB transaction; release lock ONLY if transaction fails/rolls back
        TransferResponse response;
        try {
            response = transactionTemplate.execute(
                    status -> executeTransferTx(senderUserId, idempotencyKey, request)
            );
            if (response == null) {
                throw new IllegalStateException("Transfer transaction returned null response");
            }
        } catch (Exception e) {
            log.error("Transfer failed for sender: {}, error: {}", senderUserId, e.getMessage());
            idempotencyService.releaseLock(redisKey);
            throw e;
        }

        // 4. Cache receipt post-commit without rolling back or releasing lock if Redis fails
        try {
            idempotencyService.saveReceipt(redisKey, response, RECEIPT_TTL);
        } catch (Exception ex) {
            log.warn("Failed to cache transfer receipt in Redis after commit for key={}, senderUserId={}: {}",
                    redisKey, senderUserId, ex.getMessage());
        }

        return response;
    }

    private TransferResponse executeTransferTx(Long senderUserId, String idempotencyKey, TransferRequest request) {
        // 1. Validate sender account status and fetch scalar sender wallet ID (avoid L1 entity pollution)
        User senderUser = userService.findById(senderUserId);
        if (systemUserId.equals(senderUser.getId())) {
            throw new WalletException("Không thể chuyển tiền từ tài khoản hệ thống");
        }
        if (!"ACTIVE".equals(senderUser.getStatus())) {
            throw new UserLockedException("Tài khoản người gửi đã bị khóa");
        }

        Long senderWalletId = walletRepository.findIdByUserId(senderUser.getId())
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người gửi"));
        if (systemWalletId.equals(senderWalletId)) {
            throw new WalletException("Không thể chuyển tiền từ tài khoản hệ thống");
        }

        // 2. Scoped 64-char SHA-256 DB idempotency key + DB fallback (if Redis lost cache or TTL expired)
        String hashedIdempotencyKey = DigestUtils.sha256Hex("P2P:" + senderUserId + ":" + idempotencyKey);
        Optional<Transaction> existingTxOpt = transactionRepository.findByIdempotencyKey(hashedIdempotencyKey);
        if (existingTxOpt.isPresent()) {
            Transaction existingTx = existingTxOpt.get();
            if (!senderWalletId.equals(existingTx.getSenderWalletId())
                    || existingTx.getType() != TransactionType.P2P_TRANSFER) {
                throw new IdempotencyConflictException("Idempotency key đã được sử dụng");
            }
            log.info("Returning existing DB transaction for idempotencyKey hash: {}", hashedIdempotencyKey);
            return mapToResponse(existingTx, existingTx.getCreatedAt());
        }

        // 3. Validate receiver account status and fetch scalar receiver wallet ID
        User receiverUser = userService.findByPhoneNumber(request.getReceiverPhone());
        if (systemUserId.equals(receiverUser.getId())) {
            throw new WalletException("Không thể chuyển tiền tới tài khoản hệ thống");
        }
        if (!"ACTIVE".equals(receiverUser.getStatus())) {
            throw new WalletException("Tài khoản người nhận không ở trạng thái hoạt động");
        }

        Long receiverWalletId = walletRepository.findIdByUserId(receiverUser.getId())
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người nhận"));
        if (systemWalletId.equals(receiverWalletId)) {
            throw new WalletException("Không thể chuyển tiền tới tài khoản hệ thống");
        }

        // 4. Prevent self-transfer before acquiring DB locks
        if (senderWalletId.equals(receiverWalletId)) {
            throw new WalletException("Không thể tự chuyển tiền cho chính mình");
        }

        // 5. Verify sender PIN before acquiring pessimistic DB row locks (BCrypt is CPU-bound)
        userService.verifyPin(senderUserId, request.getPin());

        // 6. Deterministic pessimistic locking in ascending ID order (min -> max) to prevent deadlocks
        Long firstId = Math.min(senderWalletId, receiverWalletId);
        Long secondId = Math.max(senderWalletId, receiverWalletId);

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + firstId));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + secondId));

        Wallet lockedSender = senderWalletId.equals(firstId) ? firstLocked : secondLocked;
        Wallet lockedReceiver = receiverWalletId.equals(firstId) ? firstLocked : secondLocked;

        // 7. Re-verify wallet statuses and balance strictly on locked rows (eliminates TOCTOU)
        if (lockedSender.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người gửi không ở trạng thái hoạt động: " + lockedSender.getStatus());
        }
        if (lockedReceiver.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người nhận không ở trạng thái hoạt động: " + lockedReceiver.getStatus());
        }
        if (lockedSender.getBalance() == null || lockedSender.getBalance() < request.getAmount()) {
            throw new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch");
        }

        // 8. Record transaction and execute double-entry balance mutation
        Transaction tx = Transaction.builder()
                .idempotencyKey(hashedIdempotencyKey)
                .senderWallet(lockedSender)
                .receiverWallet(lockedReceiver)
                .amount(request.getAmount())
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description(request.getDescription())
                .build();
        tx = transactionRepository.save(tx);

        ledgerService.executeDoubleEntry(tx, lockedSender, lockedReceiver, request.getAmount());

        // 9. Synchronize timestamp across event and response
        Instant occurredAt = tx.getCreatedAt() != null ? tx.getCreatedAt() : Instant.now();
        eventPublisher.publishEvent(TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .transactionId(tx.getId())
                .transactionType(TransactionType.P2P_TRANSFER)
                .senderUserId(senderUser.getId())
                .senderPhone(senderUser.getPhoneNumber())
                .senderName(senderUser.getFullName())
                .receiverUserId(receiverUser.getId())
                .receiverPhone(receiverUser.getPhoneNumber())
                .receiverName(receiverUser.getFullName())
                .amount(request.getAmount())
                .description(tx.getDescription())
                .occurredAt(occurredAt)
                .build());

        return mapToResponse(tx, occurredAt);
    }

    private TransferResponse mapToResponse(Transaction tx, Instant createdAt) {
        return TransferResponse.builder()
                .success(true)
                .transactionId(tx.getId())
                .senderWalletId(tx.getSenderWalletId())
                .receiverWalletId(tx.getReceiverWalletId())
                .amount(tx.getAmount())
                .status(tx.getStatus())
                .createdAt(createdAt)
                .build();
    }
}
