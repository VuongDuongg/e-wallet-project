package com.meme.vwallet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class TokenReuseException extends RuntimeException {

    public TokenReuseException(String message) {
        super(message);
    }

    public TokenReuseException() {
        super("Phát hiện token đã qua sử dụng hoặc phiên đăng nhập không hợp lệ");
    }
}
