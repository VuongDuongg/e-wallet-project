package com.meme.vwallet.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopupRequest {

    @NotNull(message = "Số tiền nạp không được để trống")
    @Positive(message = "Số tiền nạp phải lớn hơn 0")
    private Long amount;

    private String description;
}
