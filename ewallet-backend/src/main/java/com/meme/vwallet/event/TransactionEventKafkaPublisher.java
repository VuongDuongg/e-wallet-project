package com.meme.vwallet.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes committed transaction events to Kafka after the DB transaction commits.
 * Exceptions are caught and logged so Kafka outages never fail caller flows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventKafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${vwallet.kafka.topics.transaction-completed:vwallet.transaction.completed}")
    private String topic = "vwallet.transaction.completed";

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        if (event == null) {
            return;
        }
        try {
            String key = event.getTransactionId() != null
                    ? event.getTransactionId().toString()
                    : event.getEventId();
            String payloadJson = objectMapper.writeValueAsString(event);
            var future = kafkaTemplate.send(topic, key, payloadJson);
            if (future != null) {
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish TransactionCompletedEvent txId={} to topic={}",
                                event.getTransactionId(), topic, ex);
                    }
                });
            }
        } catch (Exception ex) {
            log.error("Error publishing TransactionCompletedEvent txId={} to topic={}",
                    event.getTransactionId(), topic, ex);
        }
    }
}
