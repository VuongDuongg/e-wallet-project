package com.meme.vwallet.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for parsing and verifying a scanned vWallet QR code URI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrParseRequest {

    @NotBlank(message = "Nội dung mã QR không được để trống")
    private String qrContent;
}
