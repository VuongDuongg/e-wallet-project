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
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.repository.WithdrawalRepository;
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

/**
 * Service handling idempotent cash-out withdrawals via double-entry ledger settlement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final Long SYSTEM_WALLET_ID = 1L;
    private static final Duration RECEIPT_TTL = Duration.ofHours(24);

    private final TransactionTemplate transactionTemplate;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final UserService userService;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${vwallet.withdraw.min-amount:10000}")
    private long minAmount = 10_000L;

    @Value("${vwallet.withdraw.max-amount:50000000}")
    private long maxAmount = 50_000_000L;

    /**
     * Executes an idempotent withdrawal from the user's wallet to an external bank account.
     * Redis idempotency check/locking happens outside the DB transaction boundary.
     */
    public WithdrawalResponse withdraw(Long userId, String idempotencyKey, WithdrawalRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key không được để trống");
        }
        if (request == null) {
            throw new IllegalArgumentException("Yêu cầu rút tiền không hợp lệ");
        }

        String redisKey = idempotencyService.formatWithdrawKey(userId, idempotencyKey);

        Optional<WithdrawalResponse> cachedReceipt = idempotencyService.getReceipt(redisKey, WithdrawalResponse.class);
        if (cachedReceipt.isPresent()) {
            log.info("Returning cached withdrawal response for key: {}", redisKey);
            return cachedReceipt.get();
        }

        boolean locked = idempotencyService.acquireLock(redisKey);
        if (!locked) {
            log.warn("Idempotency conflict for withdrawal key: {}", redisKey);
            throw new IdempotencyConflictException("Giao dịch rút tiền đang được xử lý, vui lòng không gửi lại yêu cầu");
        }

        WithdrawalResponse response;
        try {
            validateAmountBounds(request.getAmount());

            response = transactionTemplate.execute(
                    status -> executeWithdrawTx(userId, idempotencyKey, request)
            );
            if (response == null) {
                throw new IllegalStateException("Withdrawal transaction returned null response");
            }
        } catch (Exception e) {
            log.error("Withdrawal failed for userId: {}, error: {}", userId, e.getMessage());
            idempotencyService.releaseLock(redisKey);
            throw e;
        }

        try {
            idempotencyService.saveReceipt(redisKey, response, RECEIPT_TTL);
        } catch (Exception ex) {
            log.warn("Failed to cache withdrawal receipt in Redis after commit for key={}, userId={}: {}",
                    redisKey, userId, ex.getMessage());
        }
        return response;
    }

    private void validateAmountBounds(Long amount) {
        if (amount == null || amount < minAmount || amount > maxAmount) {
            throw new IllegalArgumentException(
                    "Số tiền rút phải nằm trong khoảng từ " + minAmount + " đến " + maxAmount + " VND"
            );
        }
    }

    private WithdrawalResponse executeWithdrawTx(Long userId, String idempotencyKey, WithdrawalRequest request) {
        User user = userService.findById(userId);
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new UserLockedException();
        }

        Long userWalletId = walletRepository.findIdByUserId(userId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người dùng"));
        if (SYSTEM_WALLET_ID.equals(userWalletId)) {
            throw new WalletException("Không thể rút tiền từ ví hệ thống");
        }

        userService.verifyPin(userId, request.getPin());

        Long firstId = Math.min(SYSTEM_WALLET_ID, userWalletId);
        Long secondId = Math.max(SYSTEM_WALLET_ID, userWalletId);

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + firstId));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + secondId));

        Wallet lockedSystemWallet = SYSTEM_WALLET_ID.equals(firstId) ? firstLocked : secondLocked;
        Wallet lockedUserWallet = userWalletId.equals(firstId) ? firstLocked : secondLocked;

        if (lockedUserWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người dùng không ở trạng thái hoạt động: " + lockedUserWallet.getStatus());
        }
        if (lockedUserWallet.getBalance() == null || lockedUserWallet.getBalance() < request.getAmount()) {
            throw new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch");
        }

        String hashedIdempotencyKey = DigestUtils.sha256Hex("WD:" + userId + ":" + idempotencyKey);
        String bankRef = "MOCK_BANK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String description = request.getDescription() != null && !request.getDescription().isBlank()
                ? request.getDescription()
                : "Rút tiền về " + request.getBankCode() + " - " + request.getBankAccountNumber();

        Transaction tx = Transaction.builder()
                .idempotencyKey(hashedIdempotencyKey)
                .senderWallet(lockedUserWallet)
                .receiverWallet(lockedSystemWallet)
                .amount(request.getAmount())
                .type(TransactionType.WITHDRAW)
                .status(TransactionStatus.COMPLETED)
                .description(description)
                .externalRef(bankRef)
                .build();
        tx = transactionRepository.save(tx);

        ledgerService.executeDoubleEntry(tx, lockedUserWallet, lockedSystemWallet, request.getAmount());

        Withdrawal withdrawal = Withdrawal.builder()
                .transaction(tx)
                .user(user)
                .bankCode(request.getBankCode())
                .bankAccountNumber(request.getBankAccountNumber())
                .accountHolderName(request.getAccountHolderName())
                .amount(request.getAmount())
                .bankRef(bankRef)
                .build();
        withdrawal = withdrawalRepository.save(withdrawal);

        Instant occurredAt = withdrawal.getCreatedAt() != null
                ? withdrawal.getCreatedAt()
                : (tx.getCreatedAt() != null ? tx.getCreatedAt() : Instant.now());

        eventPublisher.publishEvent(TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .transactionId(tx.getId())
                .transactionType(TransactionType.WITHDRAW)
                .senderUserId(user.getId())
                .senderPhone(user.getPhoneNumber())
                .senderName(user.getFullName())
                .receiverUserId(null)
                .receiverPhone("BANK")
                .receiverName(request.getBankCode())
                .amount(request.getAmount())
                .description(tx.getDescription())
                .occurredAt(occurredAt)
                .build());

        return WithdrawalResponse.builder()
                .success(true)
                .withdrawalId(withdrawal.getId())
                .transactionId(tx.getId())
                .bankCode(withdrawal.getBankCode())
                .bankAccountNumber(withdrawal.getBankAccountNumber())
                .accountHolderName(withdrawal.getAccountHolderName())
                .amount(withdrawal.getAmount())
                .status(TransactionStatus.COMPLETED)
                .bankRef(withdrawal.getBankRef())
                .balanceAfter(lockedUserWallet.getBalance())
                .createdAt(occurredAt)
                .build();
    }
}
