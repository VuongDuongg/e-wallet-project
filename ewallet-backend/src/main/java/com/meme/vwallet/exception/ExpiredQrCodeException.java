package com.meme.vwallet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ExpiredQrCodeException extends RuntimeException {

    public ExpiredQrCodeException(String message) {
        super(message);
    }
}
