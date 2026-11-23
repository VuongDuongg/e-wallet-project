package com.meme.vwallet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PinNotSetException extends RuntimeException {

    public PinNotSetException(String message) {
        super(message);
    }

    public PinNotSetException() {
        super("Tài khoản chưa cài đặt mã PIN giao dịch. Vui lòng thiết lập mã PIN trước khi thực hiện giao dịch.");
    }
}
