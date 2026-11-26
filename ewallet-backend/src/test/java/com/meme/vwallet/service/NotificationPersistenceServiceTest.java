package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.entity.Notification;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.enums.NotificationType;
import com.meme.vwallet.repository.NotificationRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPersistenceServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private NotificationPersistenceService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new NotificationPersistenceService(
                notificationRepository,
                userRepository,
                transactionRepository,
                transactionManager
        );
    }

    @Test
    @DisplayName("saveIfAbsent uses getReferenceById with REQUIRES_NEW and returns NotificationResponse on new insert")
    void saveIfAbsent_newInsert_usesGetReferenceByIdAndReturnsResponse() {
        User userRef = User.builder().id(2L).build();
        Transaction txRef = Transaction.builder().id(100L).build();
        Instant createdAt = Instant.parse("2026-11-26T07:20:00Z");

        when(notificationRepository.existsByUserIdAndTransactionIdAndType(2L, 100L, NotificationType.TRANSFER_SENT))
                .thenReturn(false);
        when(userRepository.getReferenceById(2L)).thenReturn(userRef);
        when(transactionRepository.getReferenceById(100L)).thenReturn(txRef);
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(55L);
            n.setCreatedAt(createdAt);
            return n;
        });

        Optional<NotificationResponse> result = service.saveIfAbsent(
                2L,
                100L,
                NotificationType.TRANSFER_SENT,
                "Chuyển tiền thành công",
                "Bạn đã chuyển 200,000 VND"
        );

        assertThat(result).isPresent();
        NotificationResponse response = result.get();
        assertThat(response.getId()).isEqualTo(55L);
        assertThat(response.getTransactionId()).isEqualTo(100L);
        assertThat(response.getType()).isEqualTo(NotificationType.TRANSFER_SENT);
        assertThat(response.getTitle()).isEqualTo("Chuyển tiền thành công");
        assertThat(response.getMessage()).isEqualTo("Bạn đã chuyển 200,000 VND");
        assertThat(response.isRead()).isFalse();
        assertThat(response.getCreatedAt()).isEqualTo(createdAt);

        ArgumentCaptor<TransactionDefinition> defCaptor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(defCaptor.capture());
        assertThat(defCaptor.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager).commit(any());

        verify(userRepository).getReferenceById(2L);
        verify(transactionRepository).getReferenceById(100L);
        verify(userRepository, never()).findById(any());
        verify(transactionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("saveIfAbsent skips transaction getReferenceById when txId is null")
    void saveIfAbsent_nullTxId_doesNotLookupTransaction() {
        User userRef = User.builder().id(2L).build();
        when(userRepository.getReferenceById(2L)).thenReturn(userRef);
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(56L);
            n.setCreatedAt(Instant.now());
            return n;
        });

        Optional<NotificationResponse> result = service.saveIfAbsent(
                2L,
                null,
                NotificationType.TOPUP_SUCCESS,
                "Nạp tiền thành công",
                "Bạn đã nạp 100,000 VND"
        );

        assertThat(result).isPresent();
        assertThat(result.get().getTransactionId()).isNull();
        verify(notificationRepository, never()).existsByUserIdAndTransactionIdAndType(any(), any(), any());
        verify(transactionRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("saveIfAbsent returns Optional.empty when notification already exists")
    void saveIfAbsent_alreadyExists_returnsEmptyWithoutInsert() {
        when(notificationRepository.existsByUserIdAndTransactionIdAndType(2L, 100L, NotificationType.TOPUP_SUCCESS))
                .thenReturn(true);

        Optional<NotificationResponse> result = service.saveIfAbsent(
                2L,
                100L,
                NotificationType.TOPUP_SUCCESS,
                "Nạp tiền thành công",
                "Duplicate"
        );

        assertThat(result).isEmpty();
        verify(userRepository, never()).getReferenceById(any());
        verify(transactionRepository, never()).getReferenceById(any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("saveIfAbsent catches DataIntegrityViolationException outside TransactionTemplate.execute and returns Optional.empty")
    void saveIfAbsent_dataIntegrityViolationInTx_rollsBackRequiresNewAndReturnsEmpty() {
        User userRef = User.builder().id(2L).build();
        Transaction txRef = Transaction.builder().id(100L).build();

        when(notificationRepository.existsByUserIdAndTransactionIdAndType(2L, 100L, NotificationType.TRANSFER_RECEIVED))
                .thenReturn(false);
        when(userRepository.getReferenceById(2L)).thenReturn(userRef);
        when(transactionRepository.getReferenceById(100L)).thenReturn(txRef);
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for uk_notif_idemp"));

        Optional<NotificationResponse> result = service.saveIfAbsent(
                2L,
                100L,
                NotificationType.TRANSFER_RECEIVED,
                "Nhận tiền thành công",
                "Bạn đã nhận 200,000 VND"
        );

        assertThat(result).isEmpty();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}
