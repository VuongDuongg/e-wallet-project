package com.meme.vwallet.controller;

import com.meme.vwallet.dto.response.WalletResponse;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.GlobalExceptionHandler;
import com.meme.vwallet.security.CurrentUserArgumentResolver;
import com.meme.vwallet.service.payment.PaymentService;
import com.meme.vwallet.support.SecurityTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private WalletController walletController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(walletController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityTestUtils.clearSecurityContext();
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me - Should return wallet balance when authenticated")
    void getMyWallet_authenticated_returns200() throws Exception {
        SecurityTestUtils.setAuthenticatedUser(1L, "0901234567", "Nguyen Van A");

        WalletResponse response = WalletResponse.builder()
                .id(10L)
                .walletId(10L)
                .userId(1L)
                .balance(500_000L)
                .currency("VND")
                .status(WalletStatus.ACTIVE)
                .updatedAt(Instant.now())
                .build();

        when(paymentService.getWalletBalance(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId", is(10)))
                .andExpect(jsonPath("$.userId", is(1)))
                .andExpect(jsonPath("$.balance", is(500000)))
                .andExpect(jsonPath("$.currency", is("VND")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me - Should return 401 Unauthorized when not authenticated")
    void getMyWallet_unauthenticated_returns401() throws Exception {
        SecurityTestUtils.clearSecurityContext();

        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("GET /api/v1/wallets/me - Should return 404 Not Found when wallet not found")
    void getMyWallet_walletNotFound_returns404() throws Exception {
        SecurityTestUtils.setAuthenticatedUser(99L);

        when(paymentService.getWalletBalance(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ví của người dùng: 99"));

        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isNotFound());
    }
}
