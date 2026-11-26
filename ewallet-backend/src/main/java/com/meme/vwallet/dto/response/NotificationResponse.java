package com.meme.vwallet.dto.response;

import com.meme.vwallet.entity.Notification;
import com.meme.vwallet.entity.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private Long id;
    private Long transactionId;
    private NotificationType type;
    private String title;
    private String message;
    private boolean read;
    private Instant createdAt;

    public static NotificationResponse from(Notification notification) {
        if (notification == null) {
            return null;
        }
        return NotificationResponse.builder()
                .id(notification.getId())
                .transactionId(notification.getTransaction() != null ? notification.getTransaction().getId() : null)
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .read(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
