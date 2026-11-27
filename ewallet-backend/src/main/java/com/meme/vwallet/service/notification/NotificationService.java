package com.meme.vwallet.service.notification;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.dto.response.PageResponse;
import com.meme.vwallet.entity.Notification;
import com.meme.vwallet.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service managing user notification inbox queries, read state mutations, and ownership checks.
 * All entity-to-DTO mappings stay inside @Transactional boundaries because spring.jpa.open-in-view=false.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getNotifications(Long userId, int page, int size, boolean unreadOnly) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<Notification> notificationPage = unreadOnly
                ? notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);

        return PageResponse.from(notificationPage.map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public NotificationResponse markAsRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy thông báo: " + notificationId
                ));

        if (notification.getUser() == null || !userId.equals(notification.getUser().getId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Bạn không có quyền truy cập thông báo này"
            );
        }

        if (notification.isRead()) {
            return NotificationResponse.from(notification);
        }

        notification.setRead(true);
        Notification saved = notificationRepository.save(notification);
        return NotificationResponse.from(saved);
    }

    @Transactional
    public int markAllAsRead(Long userId) {
        return notificationRepository.markAllAsReadByUserId(userId);
    }
}
