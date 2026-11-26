package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.TransactionResponse;
import com.meme.vwallet.dto.response.VnpayIpnResponse;
import com.meme.vwallet.dto.response.WalletResponse;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.GlobalExceptionHandler;
import com.meme.vwallet.security.CurrentUserArgumentResolver;
import com.meme.vwallet.service.PaymentService;
import com.meme.vwallet.service.TransactionQueryService;
import com.meme.vwallet.support.SecurityTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PaymentService paymentService;

    @Mock
    private TransactionQueryService transactionQueryService;

    @InjectMocks
    private PaymentController paymentController;

    private WalletController walletController;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        walletController = new WalletController(paymentService);
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController, walletController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();

        SecurityTestUtils.setAuthenticatedUser(2L, "0901234567", "Nguyen Van A");
    }

    @AfterEach
    void tearDown() {
        SecurityTestUtils.clearSecurityContext();
    }

    @Nested
    @DisplayName("POST /api/v1/payments/topup")
    class TopupEndpointTests {

        @Test
        @DisplayName("Should initiate top-up and return payment URL on valid request")
        void shouldInitiateTopupSuccessfully() throws Exception {
            TopupRequest request = TopupRequest.builder()
                    .amount(500000L)
                    .build();

            TopupResponse response = TopupResponse.builder()
                    .transactionId(2002L)
                    .paymentUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=50000000&vnp_TxnRef=2002")
                    .build();

            when(paymentService.initiateTopup(eq(2L), any(TopupRequest.class), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/payments/topup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId", is(2002)))
                    .andExpect(jsonPath("$.paymentUrl", is("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=50000000&vnp_TxnRef=2002")));
        }

        @Test
        @DisplayName("Should return 401 when authentication is missing")
        void shouldReturn401WhenUserIdHeaderMissing() throws Exception {
            SecurityTestUtils.clearSecurityContext();
            TopupRequest request = TopupRequest.builder().amount(100000L).build();

            mockMvc.perform(post("/api/v1/payments/topup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 400 when amount is invalid")
        void shouldReturn400WhenAmountInvalid() throws Exception {
            TopupRequest request = TopupRequest.builder().amount(-500L).build();

            mockMvc.perform(post("/api/v1/payments/topup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/payments/webhook")
    class WebhookEndpointTests {

        @Test
        @DisplayName("Should process webhook and return VnpayIpnResponse")
        void shouldProcessWebhookSuccessfully() throws Exception {
            VnpayIpnResponse ipnResponse = new VnpayIpnResponse("00", "Confirm Success");

            when(paymentService.processWebhook(any())).thenReturn(ipnResponse);

            mockMvc.perform(post("/api/v1/payments/webhook")
                            .param("vnp_Amount", "50000000")
                            .param("vnp_TxnRef", "2002")
                            .param("vnp_ResponseCode", "00")
                            .param("vnp_SecureHash", "validhash"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.RspCode", is("00")))
                    .andExpect(jsonPath("$.Message", is("Confirm Success")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/payments/vnpay-return")
    class VnpayReturnEndpointTests {

        @Test
        @DisplayName("Should process return redirect and return response map")
        void shouldProcessReturnRedirectSuccessfully() throws Exception {
            Map<String, Object> returnMap = new HashMap<>();
            returnMap.put("success", true);
            returnMap.put("transactionId", "2002");
            returnMap.put("message", "Thanh toán thành công");

            when(paymentService.processReturn(any())).thenReturn(returnMap);

            mockMvc.perform(get("/api/v1/payments/vnpay-return")
                            .param("vnp_TxnRef", "2002")
                            .param("vnp_ResponseCode", "00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success", is(true)))
                    .andExpect(jsonPath("$.transactionId", is("2002")))
                    .andExpect(jsonPath("$.message", is("Thanh toán thành công")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/transactions/{id}")
    class TransactionQueryEndpointTests {

        @Test
        @DisplayName("Should return TransactionResponse for valid id")
        void shouldReturnTransactionStatus() throws Exception {
            TransactionResponse response = TransactionResponse.builder()
                    .id(2002L)
                    .transactionId(2002L)
                    .senderWalletId(1L)
                    .receiverWalletId(2L)
                    .amount(500000L)
                    .type(TransactionType.TOPUP)
                    .status(TransactionStatus.COMPLETED)
                    .externalRef("VNPay_999")
                    .createdAt(Instant.now())
                    .build();

            when(paymentService.getTransactionStatus(eq(2L), eq(2002L))).thenReturn(response);

            mockMvc.perform(get("/api/v1/transactions/2002"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId", is(2002)))
                    .andExpect(jsonPath("$.amount", is(500000)))
                    .andExpect(jsonPath("$.status", is("COMPLETED")));
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenUnauthenticated() throws Exception {
            SecurityTestUtils.clearSecurityContext();

            mockMvc.perform(get("/api/v1/transactions/2002"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 404 when transaction does not exist")
        void shouldReturn404WhenTransactionNotFound() throws Exception {
            when(paymentService.getTransactionStatus(eq(2L), eq(9999L)))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch"));

            mockMvc.perform(get("/api/v1/transactions/9999"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 when user does not own transaction (IDOR defense)")
        void shouldReturn403WhenNotOwner() throws Exception {
            when(paymentService.getTransactionStatus(eq(2L), eq(3003L)))
                    .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập thông tin giao dịch này"));

            mockMvc.perform(get("/api/v1/transactions/3003"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/wallets/me")
    class WalletQueryEndpointTests {

        @Test
        @DisplayName("Should return WalletResponse with current balance")
        void shouldReturnWalletBalance() throws Exception {
            WalletResponse response = WalletResponse.builder()
                    .id(2L)
                    .walletId(2L)
                    .userId(2L)
                    .balance(1500000L)
                    .currency("VND")
                    .status(WalletStatus.ACTIVE)
                    .updatedAt(Instant.now())
                    .build();

            when(paymentService.getWalletBalance(2L)).thenReturn(response);

            mockMvc.perform(get("/api/v1/wallets/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.walletId", is(2)))
                    .andExpect(jsonPath("$.userId", is(2)))
                    .andExpect(jsonPath("$.balance", is(1500000)))
                    .andExpect(jsonPath("$.currency", is("VND")));
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void shouldReturn401WhenHeaderMissing() throws Exception {
            SecurityTestUtils.clearSecurityContext();
            mockMvc.perform(get("/api/v1/wallets/me"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
