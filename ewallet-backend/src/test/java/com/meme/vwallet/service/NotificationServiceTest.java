package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.dto.response.PageResponse;
import com.meme.vwallet.entity.Notification;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.enums.NotificationType;
import com.meme.vwallet.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    @DisplayName("getNotifications with unreadOnly=true calls unread query and clamps page/size (max 100)")
    void getNotifications_unreadOnlyTrue_callsUnreadQuery() {
        User owner = User.builder().id(2L).build();
        Transaction tx = Transaction.builder().id(101L).build();
        Notification notification = Notification.builder()
                .id(10L)
                .user(owner)
                .transaction(tx)
                .type(NotificationType.TOPUP_SUCCESS)
                .title("Nạp tiền thành công")
                .message("Bạn đã nạp 500,000 VND vào ví")
                .read(false)
                .createdAt(Instant.parse("2026-11-26T07:00:00Z"))
                .build();

        Page<Notification> pageResult = new PageImpl<>(List.of(notification), PageRequest.of(0, 100), 1);
        when(notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(eq(2L), any(Pageable.class)))
                .thenReturn(pageResult);

        PageResponse<NotificationResponse> response = notificationService.getNotifications(2L, -3, 250, true);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(eq(2L), pageableCaptor.capture());
        verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDescIdDesc(any(), any());

        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getId()).isEqualTo(10L);
        assertThat(response.getContent().get(0).getTransactionId()).isEqualTo(101L);
        assertThat(response.getContent().get(0).isRead()).isFalse();
    }

    @Test
    @DisplayName("getNotifications with unreadOnly=false calls all-notifications query and clamps minimum size to 1")
    void getNotifications_unreadOnlyFalse_callsAllQuery() {
        User owner = User.builder().id(2L).build();
        Notification notification = Notification.builder()
                .id(11L)
                .user(owner)
                .type(NotificationType.TRANSFER_SENT)
                .title("Chuyển tiền thành công")
                .message("Bạn đã chuyển 100,000 VND")
                .read(true)
                .createdAt(Instant.parse("2026-11-26T07:05:00Z"))
                .build();

        Page<Notification> pageResult = new PageImpl<>(List.of(notification), PageRequest.of(1, 1), 2);
        when(notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(eq(2L), any(Pageable.class)))
                .thenReturn(pageResult);

        PageResponse<NotificationResponse> response = notificationService.getNotifications(2L, 1, 0, false);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByUserIdOrderByCreatedAtDescIdDesc(eq(2L), pageableCaptor.capture());
        verify(notificationRepository, never()).findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(any(), any());

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getId()).isEqualTo(11L);
        assertThat(response.getContent().get(0).isRead()).isTrue();
    }

    @Test
    @DisplayName("getUnreadCount returns repository count for user")
    void getUnreadCount_returnsCount() {
        when(notificationRepository.countByUserIdAndReadFalse(2L)).thenReturn(5L);

        long count = notificationService.getUnreadCount(2L);

        assertThat(count).isEqualTo(5L);
        verify(notificationRepository).countByUserIdAndReadFalse(2L);
    }

    @Test
    @DisplayName("markAsRead marks owner notification read=true, saves, and returns NotificationResponse")
    void markAsRead_owner_setsReadTrueAndSaves() {
        User owner = User.builder().id(2L).build();
        Transaction tx = Transaction.builder().id(55L).build();
        Notification notification = Notification.builder()
                .id(20L)
                .user(owner)
                .transaction(tx)
                .type(NotificationType.WITHDRAW_SUCCESS)
                .title("Rút tiền thành công")
                .message("Bạn đã rút 200,000 VND")
                .read(false)
                .createdAt(Instant.parse("2026-11-26T07:10:00Z"))
                .build();

        when(notificationRepository.findById(20L)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationResponse response = notificationService.markAsRead(2L, 20L);

        assertThat(notification.isRead()).isTrue();
        verify(notificationRepository).save(notification);
        assertThat(response.getId()).isEqualTo(20L);
        assertThat(response.getTransactionId()).isEqualTo(55L);
        assertThat(response.isRead()).isTrue();
    }

    @Test
    @DisplayName("markAsRead throws 403 Forbidden when notification belongs to another user")
    void markAsRead_nonOwner_throws403Forbidden() {
        User otherUser = User.builder().id(99L).build();
        Notification notification = Notification.builder()
                .id(21L)
                .user(otherUser)
                .type(NotificationType.TRANSFER_RECEIVED)
                .title("Nhận tiền thành công")
                .message("Bạn đã nhận 100,000 VND")
                .read(false)
                .build();

        when(notificationRepository.findById(21L)).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markAsRead(2L, 21L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("markAsRead throws 404 Not Found when notification does not exist")
    void markAsRead_notFound_throws404NotFound() {
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(2L, 999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("markAllAsRead delegates to bulk update repository method and returns updated count")
    void markAllAsRead_callsBulkUpdateAndReturnsCount() {
        when(notificationRepository.markAllAsReadByUserId(2L)).thenReturn(4);

        int updated = notificationService.markAllAsRead(2L);

        assertThat(updated).isEqualTo(4);
        verify(notificationRepository).markAllAsReadByUserId(2L);
    }
}
