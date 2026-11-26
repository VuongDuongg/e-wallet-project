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
public class WithdrawalResponse {

    private boolean success;
    private Long withdrawalId;
    private Long transactionId;
    private String bankCode;
    private String bankAccountNumber;
    private String accountHolderName;
    private Long amount;
    private TransactionStatus status;
    private String bankRef;
    private Long balanceAfter;
    private Instant createdAt;
}
