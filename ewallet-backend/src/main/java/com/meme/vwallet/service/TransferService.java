package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.exception.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final Duration RECEIPT_TTL = Duration.ofHours(24);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final UserService userService;
    private final IdempotencyService idempotencyService;
    private final TransferTransactionService transferTransactionService;

    /**
     * Outer non-transactional orchestration method.
     * Validates request inputs, handles Redis idempotency, verifies PIN outside the DB write transaction,
     * delegates transactional mutation to {@link TransferTransactionService}, and isolates post-commit Redis failures.
     */
    public TransferResponse transfer(Long senderUserId, String idempotencyKey, TransferRequest request) {
        validateTransferInputs(senderUserId, idempotencyKey, request);

        String redisKey = idempotencyService.formatTransferKey(senderUserId, idempotencyKey);

        // 1. Check existing cached receipt in Redis
        Optional<TransferResponse> cachedReceipt = idempotencyService.getReceipt(redisKey, TransferResponse.class);
        if (cachedReceipt.isPresent()) {
            log.info("Returning cached transfer response for key: {}", redisKey);
            return cachedReceipt.get();
        }

        // 2. Acquire Redis idempotency lock
        boolean locked = idempotencyService.acquireLock(redisKey);
        if (!locked) {
            log.warn("Idempotency conflict for key: {}", redisKey);
            throw new IdempotencyConflictException("Giao dịch đang được xử lý, vui lòng không gửi lại yêu cầu");
        }

        // 3. Verify PIN outside @Transactional, then execute transactional transfer
        TransferResponse response;
        try {
            userService.verifyPin(senderUserId, request.getPin());
            response = transferTransactionService.executeTransferTx(senderUserId, idempotencyKey, request);
            if (response == null) {
                throw new IllegalStateException("Transfer transaction returned null response");
            }
        } catch (Exception e) {
            log.error("Transfer failed for sender: {}, error: {}", senderUserId, e.getMessage());
            safeReleaseLock(redisKey);
            throw e;
        }

        // 4. Cache receipt post-commit without failing or releasing lock if Redis throws
        try {
            idempotencyService.saveReceipt(redisKey, response, RECEIPT_TTL);
        } catch (Exception ex) {
            log.warn("Failed to cache transfer receipt in Redis after commit for key={}, senderUserId={}: {}",
                    redisKey, senderUserId, ex.getMessage());
        }

        return response;
    }

    private void validateTransferInputs(Long senderUserId, String idempotencyKey, TransferRequest request) {
        if (senderUserId == null) {
            throw new IllegalArgumentException("Thông tin người gửi không hợp lệ");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key không được để trống");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key không được vượt quá " + MAX_IDEMPOTENCY_KEY_LENGTH + " ký tự");
        }
        if (request == null) {
            throw new IllegalArgumentException("Yêu cầu chuyển tiền không hợp lệ");
        }
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Số tiền chuyển phải lớn hơn 0");
        }
        if (request.getReceiverPhone() == null || request.getReceiverPhone().isBlank()) {
            throw new IllegalArgumentException("Số điện thoại người nhận không được để trống");
        }
    }

    private void safeReleaseLock(String redisKey) {
        try {
            idempotencyService.releaseLock(redisKey);
        } catch (Exception ex) {
            log.warn("Failed to release idempotency lock for key {}: {}", redisKey, ex.getMessage());
        }
    }
}
