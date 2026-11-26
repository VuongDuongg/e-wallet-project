package com.meme.vwallet.exception;

import com.meme.vwallet.dto.response.ErrorResponse;
import com.meme.vwallet.dto.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerPhaseCTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleUserNotFoundException should return 404 and USER_NOT_FOUND")
    void handleUserNotFoundException() {
        UserNotFoundException ex = new UserNotFoundException("User not found");
        ResponseEntity<ErrorResponse> response = handler.handleUserNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getCode()).isEqualTo("USER_NOT_FOUND");
        assertThat(response.getBody().getErrorCode()).isEqualTo("USER_NOT_FOUND");
        assertThat(response.getBody().getMessage()).isEqualTo("User not found");
    }

    @Test
    @DisplayName("handleInvalidPhoneFormatException should return 400 and INVALID_PHONE_FORMAT")
    void handleInvalidPhoneFormatException() {
        InvalidPhoneFormatException ex = new InvalidPhoneFormatException("Invalid phone format");
        ResponseEntity<ErrorResponse> response = handler.handleInvalidPhoneFormatException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_PHONE_FORMAT");
        assertThat(response.getBody().getErrorCode()).isEqualTo("INVALID_PHONE_FORMAT");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid phone format");
    }

    @Test
    @DisplayName("handleInvalidQrCodeException should return 400 and INVALID_QR_CODE")
    void handleInvalidQrCodeException() {
        InvalidQrCodeException ex = new InvalidQrCodeException("Invalid QR code");
        ResponseEntity<ErrorResponse> response = handler.handleInvalidQrCodeException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_QR_CODE");
        assertThat(response.getBody().getErrorCode()).isEqualTo("INVALID_QR_CODE");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid QR code");
    }

    @Test
    @DisplayName("handleExpiredQrCodeException should return 400 and EXPIRED_QR_CODE")
    void handleExpiredQrCodeException() {
        ExpiredQrCodeException ex = new ExpiredQrCodeException("QR code expired");
        ResponseEntity<ErrorResponse> response = handler.handleExpiredQrCodeException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("EXPIRED_QR_CODE");
        assertThat(response.getBody().getErrorCode()).isEqualTo("EXPIRED_QR_CODE");
        assertThat(response.getBody().getMessage()).isEqualTo("QR code expired");
    }

    @Test
    @DisplayName("handleRateLimitExceededException should return 429 and RATE_LIMIT_EXCEEDED")
    void handleRateLimitExceededException() {
        RateLimitExceededException ex = new RateLimitExceededException("Rate limit exceeded");
        ResponseEntity<ErrorResponse> response = handler.handleRateLimitExceededException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(429);
        assertThat(response.getBody().getCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(response.getBody().getErrorCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(response.getBody().getMessage()).isEqualTo("Rate limit exceeded");
    }

    @Test
    @DisplayName("PageResponse.from should map Spring Page fields accurately")
    void pageResponseFromSpringPage() {
        Page<String> springPage = new PageImpl<>(
                List.of("item-1", "item-2"),
                PageRequest.of(1, 2),
                5
        );

        PageResponse<String> mapped = PageResponse.from(springPage);

        assertThat(mapped.getContent()).containsExactly("item-1", "item-2");
        assertThat(mapped.getPage()).isEqualTo(1);
        assertThat(mapped.getSize()).isEqualTo(2);
        assertThat(mapped.getTotalElements()).isEqualTo(5);
        assertThat(mapped.getTotalPages()).isEqualTo(3);
        assertThat(mapped.isFirst()).isFalse();
        assertThat(mapped.isLast()).isFalse();
    }

    @Test
    @DisplayName("handleIllegalArgumentException should return 400 and BAD_REQUEST")
    void handleIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Tham số không hợp lệ");
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgumentException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getErrorCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getMessage()).isEqualTo("Tham số không hợp lệ");
    }

    @Test
    @DisplayName("handleMethodArgumentTypeMismatch should return 400 and BAD_REQUEST naming parameter")
    void handleMethodArgumentTypeMismatch() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("type");

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getErrorCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getMessage()).isEqualTo("Tham số không hợp lệ: type");
    }

    @Test
    @DisplayName("handleMissingServletRequestParameter should return 400 and BAD_REQUEST")
    void handleMissingServletRequestParameter() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("phone", "String");
        ResponseEntity<ErrorResponse> response = handler.handleMissingServletRequestParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getErrorCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getMessage()).isEqualTo(ex.getMessage());
    }

    @Test
    @DisplayName("handleHttpMessageNotReadable should return 400 and BAD_REQUEST")
    void handleHttpMessageNotReadable() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Malformed JSON", mock(HttpInputMessage.class));
        ResponseEntity<ErrorResponse> response = handler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getErrorCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getMessage()).isEqualTo("Dữ liệu JSON không hợp lệ");
    }
}
