package com.meme.vwallet.dto.response;

import com.meme.vwallet.entity.enums.WalletStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {

    private Long id;
    private Long walletId;
    private Long userId;
    private Long balance;
    private String currency;
    private WalletStatus status;
    private Instant updatedAt;
}
