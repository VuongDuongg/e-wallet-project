package com.meme.vwallet.service.transaction;

import com.meme.vwallet.dto.response.PageResponse;
import com.meme.vwallet.dto.response.TransactionHistoryItemResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.WalletRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only query service for paginated user transaction history.
 */
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DIRECTION_ALL = "ALL";
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;

    /**
     * Fetches paginated, filtered transaction history for a user and maps entities inside the
     * read-only transaction boundary so no lazy relations leak past OSIV=false.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionHistoryItemResponse> getHistory(
            Long userId,
            int page,
            int size,
            TransactionType type,
            TransactionStatus status,
            String direction,
            Instant from,
            Instant to
    ) {
        Wallet userWallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy ví của người dùng: " + userId
                ));

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Thời gian bắt đầu không được lớn hơn thời gian kết thúc");
        }

        String normalizedDirection = normalizeDirection(direction);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );

        Specification<Transaction> spec = buildSpecification(
                userWallet.getId(), type, status, normalizedDirection, from, to
        );

        Page<TransactionHistoryItemResponse> mappedPage = transactionRepository
                .findAll(spec, pageable)
                .map(tx -> toResponse(tx, userWallet.getId()));

        return PageResponse.from(mappedPage);
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return DIRECTION_ALL;
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT);
        if (!DIRECTION_ALL.equals(normalized)
                && !DIRECTION_IN.equals(normalized)
                && !DIRECTION_OUT.equals(normalized)) {
            throw new IllegalArgumentException("Hướng giao dịch không hợp lệ: " + direction);
        }
        return normalized;
    }

    private Specification<Transaction> buildSpecification(
            Long userWalletId,
            TransactionType type,
            TransactionStatus status,
            String direction,
            Instant from,
            Instant to
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (DIRECTION_IN.equals(direction)) {
                predicates.add(cb.equal(root.get("receiverWallet").get("id"), userWalletId));
            } else if (DIRECTION_OUT.equals(direction)) {
                predicates.add(cb.equal(root.get("senderWallet").get("id"), userWalletId));
            } else {
                predicates.add(cb.or(
                        cb.equal(root.get("senderWallet").get("id"), userWalletId),
                        cb.equal(root.get("receiverWallet").get("id"), userWalletId)
                ));
            }

            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private TransactionHistoryItemResponse toResponse(Transaction tx, Long userWalletId) {
        String direction;
        String counterpartyPhone;
        String counterpartyName;

        if (tx.getType() == TransactionType.TOPUP) {
            direction = DIRECTION_IN;
            counterpartyPhone = "SYSTEM";
            counterpartyName = "VNPay Top-up";
        } else if (tx.getType() == TransactionType.WITHDRAW) {
            direction = DIRECTION_OUT;
            counterpartyPhone = "BANK";
            counterpartyName = "Rút tiền ngân hàng";
        } else {
            boolean isIncoming = userWalletId.equals(tx.getReceiverWalletId());
            direction = isIncoming ? DIRECTION_IN : DIRECTION_OUT;
            Wallet counterpartyWallet = isIncoming ? tx.getSenderWallet() : tx.getReceiverWallet();
            User counterpartyUser = counterpartyWallet != null ? counterpartyWallet.getUser() : null;
            counterpartyPhone = counterpartyUser != null ? counterpartyUser.getPhoneNumber() : null;
            counterpartyName = counterpartyUser != null ? counterpartyUser.getFullName() : null;
        }

        return TransactionHistoryItemResponse.builder()
                .transactionId(tx.getId())
                .type(tx.getType())
                .status(tx.getStatus())
                .direction(direction)
                .amount(tx.getAmount())
                .counterpartyPhone(counterpartyPhone)
                .counterpartyName(counterpartyName)
                .description(tx.getDescription())
                .externalRef(tx.getExternalRef())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
