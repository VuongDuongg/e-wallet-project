package com.meme.vwallet.dto.response;

import com.meme.vwallet.entity.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferResponse {

    private boolean success;
    private Long transactionId;
    private Long senderWalletId;
    private Long receiverWalletId;
    private Long amount;
    private TransactionStatus status;
    private Instant createdAt;
}
