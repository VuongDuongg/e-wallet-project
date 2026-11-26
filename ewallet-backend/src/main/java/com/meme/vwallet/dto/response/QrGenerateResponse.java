package com.meme.vwallet.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO containing the signed QR URI and embedded transfer metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrGenerateResponse {

    private String qrContent;
    private String type;
    private String receiverPhone;
    private String receiverName;
    private Long amount;
    private String description;
    private Instant expiresAt;
}
