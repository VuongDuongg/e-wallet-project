package com.meme.vwallet.kafka;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.entity.enums.NotificationType;
import com.meme.vwallet.event.TransactionCompletedEvent;
import com.meme.vwallet.service.notification.NotificationPersistenceService;
import com.meme.vwallet.service.notification.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Consumes transaction completion events from Kafka, persists notifications idempotently,
 * and pushes real-time SSE notifications only for newly persisted records.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private static final int MAX_TITLE_LENGTH = 150;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final NotificationPersistenceService notificationPersistenceService;
    private final SseEmitterRegistry sseEmitterRegistry;

    @KafkaListener(
            topics = "${vwallet.kafka.topics.transaction-completed:vwallet.transaction.completed}",
            groupId = "${spring.kafka.consumer.group-id:vwallet-notification-group}"
    )
    public void consume(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            log.warn("Ignoring empty Kafka notification payload");
            return;
        }

        TransactionCompletedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, TransactionCompletedEvent.class);
        } catch (Exception ex) {
            log.error("Failed to deserialize TransactionCompletedEvent payload, dropping message: {}", payloadJson, ex);
            return;
        }

        if (event == null || event.getTransactionId() == null || event.getTransactionType() == null) {
            log.warn("Dropping invalid TransactionCompletedEvent missing transactionId or transactionType: {}", event);
            return;
        }

        String formattedAmount = formatAmount(event.getAmount());
        switch (event.getTransactionType()) {
            case P2P_TRANSFER -> {
                if (event.getSenderUserId() != null) {
                    String receiverLabel = resolveCounterpartyLabel(event.getReceiverName(), event.getReceiverPhone());
                    String msg = "Bạn đã chuyển " + formattedAmount + " VND tới " + receiverLabel;
                    persistAndPush(
                            event.getSenderUserId(),
                            event.getTransactionId(),
                            NotificationType.TRANSFER_SENT,
                            "Chuyển tiền thành công",
                            msg
                    );
                }
                if (event.getReceiverUserId() != null) {
                    String senderLabel = resolveCounterpartyLabel(event.getSenderName(), event.getSenderPhone());
                    String msg = "Bạn đã nhận " + formattedAmount + " VND từ " + senderLabel;
                    persistAndPush(
                            event.getReceiverUserId(),
                            event.getTransactionId(),
                            NotificationType.TRANSFER_RECEIVED,
                            "Nhận tiền thành công",
                            msg
                    );
                }
            }
            case TOPUP -> {
                if (event.getReceiverUserId() != null) {
                    String msg = "Bạn đã nạp thành công " + formattedAmount + " VND vào ví";
                    persistAndPush(
                            event.getReceiverUserId(),
                            event.getTransactionId(),
                            NotificationType.TOPUP_SUCCESS,
                            "Nạp tiền thành công",
                            msg
                    );
                }
            }
            case WITHDRAW -> {
                if (event.getSenderUserId() != null) {
                    String bankLabel = event.getReceiverName() != null && !event.getReceiverName().isBlank()
                            ? event.getReceiverName()
                            : "ngân hàng";
                    String msg = "Bạn đã rút thành công " + formattedAmount + " VND về " + bankLabel;
                    persistAndPush(
                            event.getSenderUserId(),
                            event.getTransactionId(),
                            NotificationType.WITHDRAW_SUCCESS,
                            "Rút tiền thành công",
                            msg
                    );
                }
            }
        }
    }

    private void persistAndPush(
            Long userId,
            Long txId,
            NotificationType type,
            String title,
            String message) {
        Optional<NotificationResponse> saved = notificationPersistenceService.saveIfAbsent(
                userId,
                txId,
                type,
                truncate(title, MAX_TITLE_LENGTH),
                truncate(message, MAX_MESSAGE_LENGTH)
        );
        saved.ifPresent(response -> sseEmitterRegistry.sendNotification(userId, response));
    }

    private String formatAmount(Long amount) {
        long safeAmount = amount != null ? amount : 0L;
        return NumberFormat.getInstance(Locale.US).format(safeAmount);
    }

    private String resolveCounterpartyLabel(String name, String phone) {
        if (name != null && !name.isBlank() && phone != null && !phone.isBlank()) {
            return name + " (" + phone + ")";
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (phone != null && !phone.isBlank()) {
            return phone;
        }
        return "người dùng";
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
