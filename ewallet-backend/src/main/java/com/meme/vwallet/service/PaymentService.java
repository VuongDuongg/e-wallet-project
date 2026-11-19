package com.meme.vwallet.service;

import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.TransactionResponse;
import com.meme.vwallet.dto.response.VnpayIpnResponse;
import com.meme.vwallet.dto.response.WalletResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Long SYSTEM_WALLET_ID = 1L;

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final UserService userService;
    private final LedgerService ledgerService;
    private final VnpayService vnpayService;

    /**
     * Phase 1: Initiates a top-up transaction in PENDING state and creates payment gateway URL.
     */
    @Transactional
    public TopupResponse initiateTopup(Long userId, TopupRequest request, String clientIp) {
        if (request == null || request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Số tiền nạp phải lớn hơn 0");
        }

        Wallet systemWallet = walletRepository.findById(SYSTEM_WALLET_ID)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví hệ thống"));

        Wallet userWallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người dùng"));

        if (userWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người dùng không ở trạng thái hoạt động: " + userWallet.getStatus());
        }

        String idempotencyKey = "TOPUP_" + System.currentTimeMillis() + "_" + userId + "_" + UUID.randomUUID().toString().substring(0, 8);
        String description = request.getDescription() != null && !request.getDescription().isBlank()
                ? request.getDescription()
                : "Nạp tiền vào ví qua VNPay";

        Transaction tx = Transaction.builder()
                .idempotencyKey(idempotencyKey)
                .senderWallet(systemWallet)
                .receiverWallet(userWallet)
                .amount(request.getAmount())
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.PENDING)
                .description(description)
                .build();
        tx = transactionRepository.save(tx);

        String orderInfo = "Topup wallet transaction " + tx.getId();
        String paymentUrl = vnpayService.createPaymentUrl(tx.getId(), request.getAmount(), orderInfo, clientIp);

        return TopupResponse.builder()
                .transactionId(tx.getId())
                .paymentUrl(paymentUrl)
                .build();
    }

    /**
     * Phase 2: Webhook callback (IPN) handler executing strict 5-step validation and balance mutation.
     */
    @Transactional
    public VnpayIpnResponse processWebhook(Map<String, String> params) {
        log.info("Processing VNPay webhook IPN callback: {}", params);

        // 1. Signature check
        if (!vnpayService.verifySignature(params)) {
            log.warn("VNPay IPN signature verification failed");
            return VnpayIpnResponse.invalidChecksum();
        }

        // 2. Lock transaction
        String txnRefStr = params.get("vnp_TxnRef");
        if (txnRefStr == null || txnRefStr.isBlank()) {
            log.warn("Missing vnp_TxnRef in IPN");
            return VnpayIpnResponse.orderNotFound();
        }

        Long txId;
        try {
            txId = Long.parseLong(txnRefStr.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid vnp_TxnRef format: {}", txnRefStr);
            return VnpayIpnResponse.orderNotFound();
        }

        Optional<Transaction> txOpt = transactionRepository.findByIdForUpdate(txId);
        if (txOpt.isEmpty()) {
            log.warn("Transaction not found for id: {}", txId);
            return VnpayIpnResponse.orderNotFound();
        }
        Transaction tx = txOpt.get();

        // 3. Idempotent status check
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("Transaction {} already completed, returning idempotent success", txId);
            return VnpayIpnResponse.orderAlreadyConfirmed();
        }

        // 4. Amount integrity check (VNPay amount is multiplied by 100)
        String vnpAmountStr = params.get("vnp_Amount");
        long vnpAmount = -1L;
        try {
            if (vnpAmountStr != null && !vnpAmountStr.isBlank()) {
                vnpAmount = Long.parseLong(vnpAmountStr.trim());
            }
        } catch (NumberFormatException e) {
            vnpAmount = -1L;
        }

        if (vnpAmount < 0 || vnpAmount != (tx.getAmount() * 100L)) {
            log.warn("Amount mismatch for transaction {}: expected {}, received {}", txId, tx.getAmount() * 100L, vnpAmount);
            tx.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(tx);
            return new VnpayIpnResponse("04", "Invalid Amount");
        }

        // 5. Balance mutation on gateway response code
        String responseCode = params.get("vnp_ResponseCode");
        String transactionNo = params.get("vnp_TransactionNo");

        if ("00".equals(responseCode)) {
            Long sysWalletId = tx.getSenderWalletId();
            Long userWalletId = tx.getReceiverWalletId();

            Long firstId = Math.min(sysWalletId, userWalletId);
            Long secondId = Math.max(sysWalletId, userWalletId);

            Wallet firstLocked = walletRepository.findByIdForUpdate(firstId)
                    .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + firstId));
            Wallet secondLocked = walletRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> new WalletException("Không tìm thấy ví: " + secondId));

            Wallet lockedSysWallet = sysWalletId.equals(firstId) ? firstLocked : secondLocked;
            Wallet lockedUserWallet = userWalletId.equals(firstId) ? firstLocked : secondLocked;

            if (lockedUserWallet.getStatus() != WalletStatus.ACTIVE) {
                throw new WalletException("Ví người dùng không ở trạng thái hoạt động: " + lockedUserWallet.getStatus());
            }

            ledgerService.executeDoubleEntry(tx, lockedSysWallet, lockedUserWallet, tx.getAmount());

            tx.setStatus(TransactionStatus.COMPLETED);
            tx.setExternalRef(transactionNo);
            transactionRepository.save(tx);

            log.info("Transaction {} successfully completed and credited to wallet {}", txId, userWalletId);
            return VnpayIpnResponse.success();
        } else {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setExternalRef(transactionNo);
            transactionRepository.save(tx);

            log.warn("Transaction {} marked FAILED with gateway responseCode: {}", txId, responseCode);
            return VnpayIpnResponse.success();
        }
    }

    /**
     * Processes VNPay return callback redirect.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> processReturn(Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        boolean validSignature = vnpayService.verifySignature(params);
        result.put("validSignature", validSignature);

        if (!validSignature) {
            result.put("success", false);
            result.put("message", "Chữ ký không hợp lệ");
            return result;
        }

        String txnRef = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");
        boolean isSuccess = "00".equals(responseCode);

        result.put("transactionId", txnRef);
        result.put("responseCode", responseCode);
        result.put("success", isSuccess);
        result.put("message", isSuccess ? "Thanh toán thành công" : "Thanh toán không thành công");
        return result;
    }

    /**
     * Retrieves transaction status by transaction ID.
     */
    @Transactional(readOnly = true)
    public TransactionResponse getTransactionStatus(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch: " + transactionId));

        return TransactionResponse.builder()
                .id(tx.getId())
                .transactionId(tx.getId())
                .senderWalletId(tx.getSenderWalletId())
                .receiverWalletId(tx.getReceiverWalletId())
                .amount(tx.getAmount())
                .type(tx.getType())
                .status(tx.getStatus())
                .description(tx.getDescription())
                .externalRef(tx.getExternalRef())
                .createdAt(tx.getCreatedAt())
                .build();
    }

    /**
     * Retrieves user wallet information and current balance.
     */
    @Transactional(readOnly = true)
    public WalletResponse getWalletBalance(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ví của người dùng: " + userId));

        return WalletResponse.builder()
                .id(wallet.getId())
                .walletId(wallet.getId())
                .userId(wallet.getUserId())
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .status(wallet.getStatus())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }
}
