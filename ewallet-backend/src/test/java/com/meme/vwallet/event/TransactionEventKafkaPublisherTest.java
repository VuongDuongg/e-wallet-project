package com.meme.vwallet.event;

import com.meme.vwallet.entity.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionEventKafkaPublisherTest {

    private static final String TOPIC = "vwallet.transaction.completed";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private TransactionEventKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisher = new TransactionEventKafkaPublisher(kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("onTransactionCompleted serializes event including Instant to JSON and sends with txId key")
    void onTransactionCompleted_serializesAndSendsToKafka() {
        Instant occurredAt = Instant.parse("2026-11-26T07:15:30.123Z");
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-100")
                .transactionId(501L)
                .transactionType(TransactionType.P2P_TRANSFER)
                .senderUserId(2L)
                .senderPhone("0901234567")
                .senderName("Nguyen Van A")
                .receiverUserId(3L)
                .receiverPhone("0987654321")
                .receiverName("Tran Thi B")
                .amount(250_000L)
                .description("Cafe")
                .occurredAt(occurredAt)
                .build();

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(TOPIC), eq("501"), anyString())).thenReturn(future);

        publisher.onTransactionCompleted(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("501"), payloadCaptor.capture());

        String payloadJson = payloadCaptor.getValue();
        JsonNode json = objectMapper.readTree(payloadJson);
        assertThat(json.get("eventId").asString()).isEqualTo("evt-100");
        assertThat(json.get("transactionId").asLong()).isEqualTo(501L);
        assertThat(json.get("transactionType").asString()).isEqualTo("P2P_TRANSFER");
        assertThat(json.get("senderUserId").asLong()).isEqualTo(2L);
        assertThat(json.get("receiverUserId").asLong()).isEqualTo(3L);
        assertThat(json.get("amount").asLong()).isEqualTo(250_000L);

        TransactionCompletedEvent deserialized = objectMapper.readValue(payloadJson, TransactionCompletedEvent.class);
        assertThat(deserialized).isEqualTo(event);
        assertThat(deserialized.getOccurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("onTransactionCompleted swallows and logs exception when kafkaTemplate.send throws")
    void onTransactionCompleted_whenKafkaSendThrows_swallowsException() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-101")
                .transactionId(502L)
                .transactionType(TransactionType.WITHDRAW)
                .senderUserId(2L)
                .amount(100_000L)
                .occurredAt(Instant.now())
                .build();

        when(kafkaTemplate.send(eq(TOPIC), eq("502"), anyString()))
                .thenThrow(new RuntimeException("Kafka broker unavailable"));

        assertThatCode(() -> publisher.onTransactionCompleted(event))
                .doesNotThrowAnyException();

        verify(kafkaTemplate).send(eq(TOPIC), eq("502"), anyString());
    }

    @Test
    @DisplayName("onTransactionCompleted handles failed future asynchronously without throwing")
    void onTransactionCompleted_whenKafkaFutureFails_doesNotThrow() {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
                .eventId("evt-102")
                .transactionId(503L)
                .transactionType(TransactionType.TOPUP)
                .senderUserId(1L)
                .receiverUserId(2L)
                .amount(500_000L)
                .occurredAt(Instant.now())
                .build();

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Async send error"));
        when(kafkaTemplate.send(eq(TOPIC), eq("503"), anyString())).thenReturn(failedFuture);

        assertThatCode(() -> publisher.onTransactionCompleted(event))
                .doesNotThrowAnyException();
    }
}
