package com.meme.vwallet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WithdrawalRequest {

    @NotBlank(message = "Mã ngân hàng không được để trống")
    @Pattern(regexp = "^[A-Z0-9_]{2,20}$", message = "Mã ngân hàng không hợp lệ")
    private String bankCode;

    @NotBlank(message = "Số tài khoản ngân hàng không được để trống")
    @Pattern(regexp = "^[0-9]{6,20}$", message = "Số tài khoản ngân hàng phải gồm 6-20 chữ số")
    private String bankAccountNumber;

    @NotBlank(message = "Tên chủ tài khoản không được để trống")
    @Size(min = 2, max = 100, message = "Tên chủ tài khoản phải từ 2 đến 100 ký tự")
    private String accountHolderName;

    @NotNull(message = "Số tiền rút không được để trống")
    @Positive(message = "Số tiền rút phải lớn hơn 0")
    private Long amount;

    @NotBlank(message = "Mã PIN không được để trống")
    @Pattern(regexp = "^[0-9]{6}$", message = "Mã PIN phải gồm 6 chữ số")
    private String pin;

    @Size(max = 255, message = "Nội dung rút tiền tối đa 255 ký tự")
    private String description;
}
