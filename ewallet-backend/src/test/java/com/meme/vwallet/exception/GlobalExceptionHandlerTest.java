package com.meme.vwallet.exception;

import com.meme.vwallet.dto.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handlePinNotSetException should return 400 and PIN_NOT_SET")
    void handlePinNotSetException() {
        PinNotSetException ex = new PinNotSetException("PIN chưa thiết lập");
        ResponseEntity<ErrorResponse> response = handler.handlePinNotSetException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("PIN_NOT_SET");
        assertThat(response.getBody().getErrorCode()).isEqualTo("PIN_NOT_SET");
        assertThat(response.getBody().getMessage()).isEqualTo("PIN chưa thiết lập");
    }

    @Test
    @DisplayName("handlePinAlreadySetException should return 400 and PIN_ALREADY_SET")
    void handlePinAlreadySetException() {
        PinAlreadySetException ex = new PinAlreadySetException("PIN đã thiết lập");
        ResponseEntity<ErrorResponse> response = handler.handlePinAlreadySetException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("PIN_ALREADY_SET");
        assertThat(response.getBody().getErrorCode()).isEqualTo("PIN_ALREADY_SET");
        assertThat(response.getBody().getMessage()).isEqualTo("PIN đã thiết lập");
    }

    @Test
    @DisplayName("handleInvalidTokenTypeException should return 401 and INVALID_TOKEN_TYPE")
    void handleInvalidTokenTypeException() {
        InvalidTokenTypeException ex = new InvalidTokenTypeException("Invalid token type");
        ResponseEntity<ErrorResponse> response = handler.handleInvalidTokenTypeException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_TOKEN_TYPE");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid token type");
    }

    @Test
    @DisplayName("handleTokenReuseException should return 401 and TOKEN_REUSE_DETECTED")
    void handleTokenReuseException() {
        TokenReuseException ex = new TokenReuseException("Token reuse detected");
        ResponseEntity<ErrorResponse> response = handler.handleTokenReuseException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getCode()).isEqualTo("TOKEN_REUSE_DETECTED");
        assertThat(response.getBody().getMessage()).isEqualTo("Token reuse detected");
    }

    @Test
    @DisplayName("handleBadCredentialsException should return 401, BAD_CREDENTIALS, and Vietnamese message")
    void handleBadCredentialsException() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");
        ResponseEntity<ErrorResponse> response = handler.handleBadCredentialsException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_CREDENTIALS");
        assertThat(response.getBody().getMessage()).isEqualTo("Số điện thoại hoặc mật khẩu không chính xác");
    }

    @Test
    @DisplayName("handleUserLockedException should return 403 and USER_LOCKED")
    void handleUserLockedException() {
        UserLockedException ex = new UserLockedException("User locked");
        ResponseEntity<ErrorResponse> response = handler.handleUserLockedException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(403);
        assertThat(response.getBody().getCode()).isEqualTo("USER_LOCKED");
        assertThat(response.getBody().getMessage()).isEqualTo("User locked");
    }

    @Test
    @DisplayName("handleUserAlreadyExistsException should return 409 and USER_ALREADY_EXISTS")
    void handleUserAlreadyExistsException() {
        UserAlreadyExistsException ex = new UserAlreadyExistsException("User already exists");
        ResponseEntity<ErrorResponse> response = handler.handleUserAlreadyExistsException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo("USER_ALREADY_EXISTS");
        assertThat(response.getBody().getMessage()).isEqualTo("User already exists");
    }

    @Test
    @DisplayName("handleConflictException should return 409 and CONFLICT")
    void handleConflictException() {
        ConflictException ex = new ConflictException("Conflict occurred");
        ResponseEntity<ErrorResponse> response = handler.handleConflictException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo("CONFLICT");
        assertThat(response.getBody().getMessage()).isEqualTo("Conflict occurred");
    }

    @Test
    @DisplayName("handleInvalidPinException should return 400 and INVALID_PIN")
    void handleInvalidPinException() {
        InvalidPinException ex = new InvalidPinException("Invalid PIN");
        ResponseEntity<ErrorResponse> response = handler.handleInvalidPinException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_PIN");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid PIN");
    }
}
