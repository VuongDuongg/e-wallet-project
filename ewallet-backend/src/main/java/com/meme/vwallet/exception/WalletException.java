package com.meme.vwallet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class WalletException extends RuntimeException {

    public WalletException(String message) {
        super(message);
    }

    public WalletException(String message, Throwable cause) {
        super(message, cause);
    }
}
