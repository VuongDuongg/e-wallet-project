package com.meme.vwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO returning verified QR metadata and live recipient eligibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrParseResponse {

    private String type;
    private String receiverPhone;
    private String receiverName;
    private Long amount;
    private String description;

    @JsonProperty("isSelf")
    private boolean isSelf;

    private boolean canTransfer;
    private Instant expiresAt;
}
