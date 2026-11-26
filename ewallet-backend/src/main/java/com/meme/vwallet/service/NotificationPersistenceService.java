package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.entity.Notification;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.enums.NotificationType;
import com.meme.vwallet.repository.NotificationRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Persists notifications idempotently in an isolated REQUIRES_NEW transaction.
 * Catches DataIntegrityViolationException outside TransactionTemplate.execute()
 * to avoid UnexpectedRollbackException on duplicate inserts.
 */
@Slf4j
@Service
public class NotificationPersistenceService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionTemplate requiresNewTxTemplate;

    @Autowired
    public NotificationPersistenceService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            TransactionRepository transactionRepository,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<NotificationResponse> saveIfAbsent(
            Long userId,
            Long txId,
            NotificationType type,
            String title,
            String message) {
        if (userId == null || type == null) {
            return Optional.empty();
        }
        try {
            NotificationResponse response = requiresNewTxTemplate.execute(status -> {
                if (txId != null && notificationRepository.existsByUserIdAndTransactionIdAndType(userId, txId, type)) {
                    return null;
                }
                User userRef = userRepository.getReferenceById(userId);
                Transaction txRef = txId != null ? transactionRepository.getReferenceById(txId) : null;
                Notification notification = Notification.builder()
                        .user(userRef)
                        .transaction(txRef)
                        .type(type)
                        .title(title)
                        .message(message)
                        .read(false)
                        .build();
                Notification saved = notificationRepository.saveAndFlush(notification);
                return NotificationResponse.from(saved);
            });
            return Optional.ofNullable(response);
        } catch (DataIntegrityViolationException ex) {
            log.info("Duplicate notification ignored for userId={}, txId={}, type={}", userId, txId, type);
            return Optional.empty();
        }
    }
}
