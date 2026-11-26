package com.meme.vwallet.event;

import com.meme.vwallet.entity.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * Flat immutable event DTO emitted after financial transaction commits.
 * Carries scalar fields only so no JPA entity or lazy proxy crosses transaction boundaries.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class TransactionCompletedEvent {

    private String eventId;
    private Long transactionId;
    private TransactionType transactionType;
    private Long senderUserId;
    private String senderPhone;
    private String senderName;
    private Long receiverUserId;
    private String receiverPhone;
    private String receiverName;
    private Long amount;
    private String description;
    private Instant occurredAt;
}
