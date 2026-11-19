package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.exception.GlobalExceptionHandler;
import com.meme.vwallet.exception.IdempotencyConflictException;
import com.meme.vwallet.exception.InsufficientBalanceException;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.UserNotFoundException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.security.CurrentUserArgumentResolver;
import com.meme.vwallet.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TransferService transferService;

    @InjectMocks
    private TransferController transferController;

    private ObjectMapper objectMapper;
    private TransferRequest validRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(transferController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();

        validRequest = TransferRequest.builder()
                .receiverPhone("0907654321")
                .amount(200_000L)
                .pin("123456")
                .description("Tien an trua")
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/transfers - Success")
    class SuccessTests {

        @Test
        @DisplayName("should return 200 OK with TransferResponse when transfer succeeds")
        void transfer_success() throws Exception {
            Instant now = Instant.now();
            TransferResponse response = TransferResponse.builder()
                    .success(true)
                    .transactionId(100L)
                    .senderWalletId(10L)
                    .receiverWalletId(20L)
                    .amount(200_000L)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(now)
                    .build();

            when(transferService.transfer(eq(1L), eq("req-idemp-123"), any(TransferRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success", is(true)))
                    .andExpect(jsonPath("$.transactionId", is(100)))
                    .andExpect(jsonPath("$.senderWalletId", is(10)))
                    .andExpect(jsonPath("$.receiverWalletId", is(20)))
                    .andExpect(jsonPath("$.amount", is(200000)))
                    .andExpect(jsonPath("$.status", is("COMPLETED")))
                    .andExpect(jsonPath("$.createdAt", notNullValue()));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/transfers - Validation and Header Errors")
    class ValidationAndHeaderErrors {

        @Test
        @DisplayName("should return 401 UNAUTHORIZED when X-User-Id header is missing")
        void transfer_missingUserIdHeader_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/transfers")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.error", is("Unauthorized")))
                    .andExpect(jsonPath("$.message", containsString("Missing or invalid X-User-Id")));
        }

        @Test
        @DisplayName("should return 400 BAD REQUEST when Idempotency-Key header is missing")
        void transfer_missingIdempotencyKeyHeader_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")));
        }

        @Test
        @DisplayName("should return 400 BAD REQUEST when request body has invalid amount (<= 0)")
        void transfer_invalidAmount_returns400() throws Exception {
            validRequest.setAmount(-50_000L);

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Số tiền chuyển phải lớn hơn 0")));
        }

        @Test
        @DisplayName("should return 400 BAD REQUEST when request body has blank receiverPhone")
        void transfer_blankReceiverPhone_returns400() throws Exception {
            validRequest.setReceiverPhone("");

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Số điện thoại người nhận không được để trống")));
        }

        @Test
        @DisplayName("should return 400 BAD REQUEST when request body has blank pin")
        void transfer_blankPin_returns400() throws Exception {
            validRequest.setPin("");

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Mã PIN không được để trống")));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/transfers - Business Exception Handlers")
    class BusinessExceptionHandlingTests {

        @Test
        @DisplayName("should return 400 BAD REQUEST when WalletException is thrown")
        void transfer_walletException_returns400() throws Exception {
            when(transferService.transfer(eq(1L), eq("req-idemp-123"), any(TransferRequest.class)))
                    .thenThrow(new WalletException("Không thể tự chuyển tiền cho chính mình"));

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", is("Không thể tự chuyển tiền cho chính mình")));
        }

        @Test
        @DisplayName("should return 400 BAD REQUEST when InvalidPinException is thrown")
        void transfer_invalidPinException_returns400() throws Exception {
            when(transferService.transfer(eq(1L), eq("req-idemp-123"), any(TransferRequest.class)))
                    .thenThrow(new InvalidPinException("Invalid PIN"));

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", is("Invalid PIN")));
        }

        @Test
        @DisplayName("should return 400 BAD REQUEST when InsufficientBalanceException is thrown")
        void transfer_insufficientBalanceException_returns400() throws Exception {
            when(transferService.transfer(eq(1L), eq("req-idemp-123"), any(TransferRequest.class)))
                    .thenThrow(new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch"));

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", is("Số dư không đủ để thực hiện giao dịch")));
        }

        @Test
        @DisplayName("should return 404 NOT FOUND when UserNotFoundException is thrown")
        void transfer_userNotFoundException_returns404() throws Exception {
            when(transferService.transfer(eq(1L), eq("req-idemp-123"), any(TransferRequest.class)))
                    .thenThrow(new UserNotFoundException("User not found with phone number: 0907654321"));

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.error", is("Not Found")))
                    .andExpect(jsonPath("$.message", containsString("User not found")));
        }

        @Test
        @DisplayName("should return 409 CONFLICT when IdempotencyConflictException is thrown")
        void transfer_idempotencyConflictException_returns409() throws Exception {
            when(transferService.transfer(eq(1L), eq("req-idemp-123"), any(TransferRequest.class)))
                    .thenThrow(new IdempotencyConflictException("Giao dịch đang được xử lý, vui lòng không gửi lại yêu cầu"));

            mockMvc.perform(post("/api/v1/transfers")
                            .header("X-User-Id", "1")
                            .header("Idempotency-Key", "req-idemp-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.error", is("Conflict")))
                    .andExpect(jsonPath("$.message", containsString("Giao dịch đang được xử lý")));
        }
    }
}
