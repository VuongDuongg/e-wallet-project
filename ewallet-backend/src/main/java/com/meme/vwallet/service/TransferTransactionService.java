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
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferTransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final UserService userService;
    private final LedgerService ledgerService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${vwallet.system.user-id:1}")
    private Long systemUserId = 1L;

    @Value("${vwallet.system.wallet-id:1}")
    private Long systemWalletId = 1L;

    @Transactional
    public TransferResponse executeTransferTx(Long senderUserId, String idempotencyKey, TransferRequest request) {
        // 1. Validate sender account and fetch scalar wallet ID without loading Wallet into L1 cache
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

        // 2. DB-level idempotency fallback if Redis cache is missing or TTL expired
        Optional<Transaction> existingTxOpt = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTxOpt.isPresent()) {
            Transaction existingTx = existingTxOpt.get();
            if (existingTx.getSenderWallet() == null
                    || !existingTx.getSenderWallet().getId().equals(senderWalletId)
                    || existingTx.getType() != TransactionType.P2P_TRANSFER) {
                throw new IdempotencyConflictException("Idempotency key đã được sử dụng");
            }
            log.info("Returning existing DB transaction for idempotencyKey: {}", idempotencyKey);
            return mapToResponse(existingTx, existingTx.getCreatedAt());
        }

        // 3. Validate receiver account and fetch scalar wallet ID
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

        // 4. Reject self-transfer before acquiring pessimistic row locks
        if (senderWalletId.equals(receiverWalletId)) {
            throw new WalletException("Không thể tự chuyển tiền cho chính mình");
        }

        // 5. Acquire pessimistic locks in deterministic ascending ID order (min -> max)
        Long firstId = Math.min(senderWalletId, receiverWalletId);
        Long secondId = Math.max(senderWalletId, receiverWalletId);

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + firstId));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + secondId));

        Wallet lockedSender = senderWalletId.equals(firstId) ? firstLocked : secondLocked;
        Wallet lockedReceiver = receiverWalletId.equals(firstId) ? firstLocked : secondLocked;

        // 6. Validate wallet statuses strictly after locking (TOCTOU fix)
        if (lockedSender.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người gửi không ở trạng thái hoạt động: " + lockedSender.getStatus());
        }
        if (lockedReceiver.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người nhận không ở trạng thái hoạt động: " + lockedReceiver.getStatus());
        }

        // 7. Save transaction record
        Transaction tx = Transaction.builder()
                .idempotencyKey(idempotencyKey)
                .senderWallet(lockedSender)
                .receiverWallet(lockedReceiver)
                .amount(request.getAmount())
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description(request.getDescription())
                .build();
        tx = transactionRepository.save(tx);

        // 8. Execute double-entry balance mutation on locked wallets
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
                .senderWalletId(tx.getSenderWallet().getId())
                .receiverWalletId(tx.getReceiverWallet().getId())
                .amount(tx.getAmount())
                .status(tx.getStatus())
                .createdAt(createdAt)
                .build();
    }
}
