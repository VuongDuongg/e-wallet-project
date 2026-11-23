package com.meme.vwallet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class UserLockedException extends RuntimeException {

    public UserLockedException(String message) {
        super(message);
    }

    public UserLockedException() {
        super("Tài khoản người dùng đã bị khóa hoặc không hoạt động");
    }
}
