package com.meme.vwallet.dto.response;

import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionHistoryItemResponse {

    private Long transactionId;
    private TransactionType type;
    private TransactionStatus status;
    private String direction;
    private Long amount;
    private String counterpartyPhone;
    private String counterpartyName;
    private String description;
    private String externalRef;
    private Instant createdAt;
}
