package com.meme.vwallet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinSetupRequest {

    @NotBlank(message = "Mã PIN không được để trống")
    @Pattern(regexp = "^[0-9]{6}$", message = "Mã PIN phải gồm đúng 6 chữ số")
    private String pin;

    @NotBlank(message = "Xác nhận mã PIN không được để trống")
    @Pattern(regexp = "^[0-9]{6}$", message = "Xác nhận mã PIN phải gồm đúng 6 chữ số")
    private String confirmPin;
}
