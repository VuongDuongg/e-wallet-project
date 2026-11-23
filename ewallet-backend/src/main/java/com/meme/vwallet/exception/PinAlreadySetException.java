package com.meme.vwallet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PinAlreadySetException extends RuntimeException {

    public PinAlreadySetException(String message) {
        super(message);
    }

    public PinAlreadySetException() {
        super("Mã PIN giao dịch đã được thiết lập. Không thể cài đặt lại tại endpoint này.");
    }
}
