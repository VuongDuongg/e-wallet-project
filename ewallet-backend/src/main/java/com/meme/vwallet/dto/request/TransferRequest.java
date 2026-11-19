package com.meme.vwallet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferRequest {

    @NotBlank(message = "Số điện thoại người nhận không được để trống")
    private String receiverPhone;

    @NotNull(message = "Số tiền không được để trống")
    @Positive(message = "Số tiền chuyển phải lớn hơn 0")
    private Long amount;

    @NotBlank(message = "Mã PIN không được để trống")
    private String pin;

    private String description;
}
