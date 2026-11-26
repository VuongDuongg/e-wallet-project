package com.meme.vwallet.kafka;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.entity.enums.NotificationType;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.event.TransactionCompletedEvent;
import com.meme.vwallet.service.NotificationPersistenceService;
import com.meme.vwallet.service.SseEmitterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock
    private NotificationPersistenceService notificationPersistenceService;

    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    private ObjectMapper objectMapper;
    private NotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new NotificationConsumer(objectMapper, notificationPersistenceService, sseEmitterRegistry);
    }

    @Test
    @DisplayName("P2P_TRANSFER deserializes JSON with Instant, persists TRANSFER_SENT & TRANSFER_RECEIVED, and pushes SSE for both")
    void consume_p2pTransfer_persistsBothAndPushesSseForBoth() {
        Instant occurredAt = Instant.parse("2026-11-26T07:30:00.456Z");
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-1")
                .transactionId(1001L)
                .transactionType(TransactionType.P2P_TRANSFER)
                .senderUserId(2L)
                .senderPhone("0901234567")
                .senderName("Nguyen Van A")
                .receiverUserId(3L)
                .receiverPhone("0987654321")
                .receiverName("Tran Thi B")
                .amount(200_000L)
                .description("Cafe")
                .occurredAt(occurredAt)
                .build();
        String payloadJson = objectMapper.writeValueAsString(event);

        NotificationResponse senderNotif = NotificationResponse.builder()
                .id(10L)
                .transactionId(1001L)
                .type(NotificationType.TRANSFER_SENT)
                .title("Chuyển tiền thành công")
                .message("msg1")
                .read(false)
                .createdAt(occurredAt)
                .build();
        NotificationResponse receiverNotif = NotificationResponse.builder()
                .id(11L)
                .transactionId(1001L)
                .type(NotificationType.TRANSFER_RECEIVED)
                .title("Nhận tiền thành công")
                .message("msg2")
                .read(false)
                .createdAt(occurredAt)
                .build();

        when(notificationPersistenceService.saveIfAbsent(
                eq(2L), eq(1001L), eq(NotificationType.TRANSFER_SENT), anyString(), anyString()))
                .thenReturn(Optional.of(senderNotif));
        when(notificationPersistenceService.saveIfAbsent(
                eq(3L), eq(1001L), eq(NotificationType.TRANSFER_RECEIVED), anyString(), anyString()))
                .thenReturn(Optional.of(receiverNotif));

        consumer.consume(payloadJson);

        verify(sseEmitterRegistry).sendNotification(2L, senderNotif);
        verify(sseEmitterRegistry).sendNotification(3L, receiverNotif);
    }

    @Test
    @DisplayName("P2P_TRANSFER skips SSE push for duplicate notification while pushing for non-duplicate")
    void consume_p2pTransferPartialDuplicate_pushesSseOnlyForNonDuplicate() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-2")
                .transactionId(1002L)
                .transactionType(TransactionType.P2P_TRANSFER)
                .senderUserId(2L)
                .senderPhone("0901234567")
                .senderName("Nguyen Van A")
                .receiverUserId(3L)
                .receiverPhone("0987654321")
                .receiverName("Tran Thi B")
                .amount(150_000L)
                .occurredAt(Instant.now())
                .build();
        String payloadJson = objectMapper.writeValueAsString(event);

        NotificationResponse receiverNotif = NotificationResponse.builder()
                .id(12L)
                .transactionId(1002L)
                .type(NotificationType.TRANSFER_RECEIVED)
                .title("Nhận tiền thành công")
                .message("msg")
                .read(false)
                .createdAt(Instant.now())
                .build();

        when(notificationPersistenceService.saveIfAbsent(
                eq(2L), eq(1002L), eq(NotificationType.TRANSFER_SENT), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(notificationPersistenceService.saveIfAbsent(
                eq(3L), eq(1002L), eq(NotificationType.TRANSFER_RECEIVED), anyString(), anyString()))
                .thenReturn(Optional.of(receiverNotif));

        consumer.consume(payloadJson);

        verify(sseEmitterRegistry, never()).sendNotification(eq(2L), any());
        verify(sseEmitterRegistry).sendNotification(3L, receiverNotif);
    }

    @Test
    @DisplayName("TOPUP persists TOPUP_SUCCESS for receiver and pushes SSE")
    void consume_topup_persistsTopupSuccessToReceiver() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-3")
                .transactionId(1003L)
                .transactionType(TransactionType.TOPUP)
                .senderUserId(1L)
                .receiverUserId(2L)
                .receiverPhone("0901234567")
                .receiverName("Nguyen Van A")
                .amount(500_000L)
                .occurredAt(Instant.now())
                .build();
        String payloadJson = objectMapper.writeValueAsString(event);

        NotificationResponse topupNotif = NotificationResponse.builder()
                .id(13L)
                .transactionId(1003L)
                .type(NotificationType.TOPUP_SUCCESS)
                .title("Nạp tiền thành công")
                .message("msg")
                .read(false)
                .createdAt(Instant.now())
                .build();

        when(notificationPersistenceService.saveIfAbsent(
                eq(2L), eq(1003L), eq(NotificationType.TOPUP_SUCCESS), anyString(), anyString()))
                .thenReturn(Optional.of(topupNotif));

        consumer.consume(payloadJson);

        verify(notificationPersistenceService, never()).saveIfAbsent(eq(1L), any(), any(), any(), any());
        verify(sseEmitterRegistry).sendNotification(2L, topupNotif);
    }

    @Test
    @DisplayName("WITHDRAW persists WITHDRAW_SUCCESS for sender and pushes SSE")
    void consume_withdraw_persistsWithdrawSuccessToSender() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-4")
                .transactionId(1004L)
                .transactionType(TransactionType.WITHDRAW)
                .senderUserId(2L)
                .senderPhone("0901234567")
                .senderName("Nguyen Van A")
                .receiverUserId(null)
                .receiverPhone("BANK")
                .receiverName("VCB")
                .amount(300_000L)
                .occurredAt(Instant.now())
                .build();
        String payloadJson = objectMapper.writeValueAsString(event);

        NotificationResponse withdrawNotif = NotificationResponse.builder()
                .id(14L)
                .transactionId(1004L)
                .type(NotificationType.WITHDRAW_SUCCESS)
                .title("Rút tiền thành công")
                .message("msg")
                .read(false)
                .createdAt(Instant.now())
                .build();

        when(notificationPersistenceService.saveIfAbsent(
                eq(2L), eq(1004L), eq(NotificationType.WITHDRAW_SUCCESS), anyString(), anyString()))
                .thenReturn(Optional.of(withdrawNotif));

        consumer.consume(payloadJson);

        verify(sseEmitterRegistry).sendNotification(2L, withdrawNotif);
    }

    @Test
    @DisplayName("malformed JSON is logged and dropped without crashing listener")
    void consume_malformedJson_doesNotThrow() {
        assertThatCode(() -> consumer.consume("{invalid-json"))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationPersistenceService, sseEmitterRegistry);
    }
}
