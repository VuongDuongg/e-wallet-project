package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.exception.IdempotencyConflictException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final Duration RECEIPT_TTL = Duration.ofHours(24);

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final UserService userService;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    @Autowired
    @Lazy
    private TransferService self;

    /**
     * Outer non-transactional orchestration method.
     * Manages idempotency check, distributed lock, and receipt caching.
     */
    public TransferResponse transfer(Long senderUserId, String idempotencyKey, TransferRequest request) {
        String redisKey = idempotencyService.formatTransferKey(senderUserId, idempotencyKey);

        // 1. Check existing cached receipt
        Optional<TransferResponse> cachedReceipt = idempotencyService.getReceipt(redisKey, TransferResponse.class);
        if (cachedReceipt.isPresent()) {
            log.info("Returning cached transfer response for key: {}", redisKey);
            return cachedReceipt.get();
        }

        // 2. Acquire idempotency lock
        boolean locked = idempotencyService.acquireLock(redisKey);
        if (!locked) {
            log.warn("Idempotency conflict for key: {}", redisKey);
            throw new IdempotencyConflictException("Giao dịch đang được xử lý, vui lòng không gửi lại yêu cầu");
        }

        try {
            // 3. Execute transactional transfer logic
            TransferResponse response = (self != null ? self : this).executeTransferTx(senderUserId, idempotencyKey, request);

            // 4. Cache successful receipt
            idempotencyService.saveReceipt(redisKey, response, RECEIPT_TTL);

            return response;
        } catch (Exception e) {
            log.error("Transfer failed for sender: {}, error: {}", senderUserId, e.getMessage());
            idempotencyService.releaseLock(redisKey);
            throw e;
        }
    }

    /**
     * Inner transactional method.
     * Performs account validation, PIN check, deadlock-free locking, and double-entry mutation.
     */
    @Transactional
    public TransferResponse executeTransferTx(Long senderUserId, String idempotencyKey, TransferRequest request) {
        // 1. Lookup sender user & wallet
        User senderUser = userService.findById(senderUserId);
        Wallet senderWallet = walletRepository.findByUserId(senderUser.getId())
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người gửi"));

        // 2. Lookup receiver user & wallet
        User receiverUser = userService.findByPhoneNumber(request.getReceiverPhone());
        Wallet receiverWallet = walletRepository.findByUserId(receiverUser.getId())
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người nhận"));

        // 3. Prevent self-transfer
        if (senderWallet.getId().equals(receiverWallet.getId())) {
            throw new WalletException("Không thể tự chuyển tiền cho chính mình");
        }

        // 4. Verify PIN
        userService.verifyPin(senderUserId, request.getPin());

        // 5. Deterministic pessimistic locking in ascending order min(idA, idB) -> max(idA, idB)
        Long firstId = Math.min(senderWallet.getId(), receiverWallet.getId());
        Long secondId = Math.max(senderWallet.getId(), receiverWallet.getId());

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + firstId));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + secondId));

        Wallet lockedSender = senderWallet.getId().equals(firstId) ? firstLocked : secondLocked;
        Wallet lockedReceiver = receiverWallet.getId().equals(firstId) ? firstLocked : secondLocked;

        // 6. Record transaction
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

        // 7. Execute double-entry balance mutation
        ledgerService.executeDoubleEntry(tx, lockedSender, lockedReceiver, request.getAmount());

        // 8. Build response
        return TransferResponse.builder()
                .success(true)
                .transactionId(tx.getId())
                .senderWalletId(lockedSender.getId())
                .receiverWalletId(lockedReceiver.getId())
                .amount(request.getAmount())
                .status(TransactionStatus.COMPLETED)
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
