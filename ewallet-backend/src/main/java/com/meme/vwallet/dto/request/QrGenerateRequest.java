package com.meme.vwallet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for generating a static or dynamic vWallet QR payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrGenerateRequest {

    @NotBlank(message = "Loại mã QR không được để trống")
    @Pattern(regexp = "^(STATIC|DYNAMIC)$", message = "Loại mã QR phải là STATIC hoặc DYNAMIC")
    private String type;

    private Long amount;

    @Size(max = 255, message = "Nội dung chuyển tiền tối đa 255 ký tự")
    private String description;
}
