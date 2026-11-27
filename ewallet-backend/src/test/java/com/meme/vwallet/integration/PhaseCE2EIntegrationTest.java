package com.meme.vwallet.integration;

import com.meme.vwallet.dto.request.PinSetupRequest;
import com.meme.vwallet.dto.request.QrGenerateRequest;
import com.meme.vwallet.dto.request.QrParseRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.request.WithdrawalRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.dto.response.QrGenerateResponse;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.dto.response.WithdrawalResponse;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.kafka.NotificationConsumer;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.NotificationRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.repository.WithdrawalRepository;
import com.meme.vwallet.service.payment.VnpayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PhaseCE2EIntegrationTest {

    private static final long INITIAL_SYSTEM_BALANCE = 1_000_000_000_000L;
    private static final String TOPIC = "vwallet.transaction.completed";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationConsumer notificationConsumer;

    @Autowired
    private VnpayService vnpayService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private MockMvc mockMvc;

    private final Map<String, Object> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        redisStore.clear();

        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any());

        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(), any(Duration.class));

        doAnswer(inv -> redisStore.get(inv.getArgument(0)))
                .when(valueOperations).get(anyString());

        doAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null)
                .when(valueOperations).setIfAbsent(anyString(), any(), any(Duration.class));

        doAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null)
                .when(redisTemplate).delete(anyString());

        doAnswer(inv -> redisStore.containsKey(inv.getArgument(0)))
                .when(redisTemplate).hasKey(anyString());

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        redisStore.clear();
        notificationRepository.deleteAll();
        withdrawalRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> u.getId() != null && u.getId() > 3L)
                .forEach(u -> {
                    walletRepository.findByUserId(u.getId()).ifPresent(walletRepository::delete);
                    userRepository.delete(u);
                });
        walletRepository.findById(1L).ifPresent(sys -> {
            sys.setBalance(INITIAL_SYSTEM_BALANCE);
            walletRepository.save(sys);
        });
    }

    private AuthResponse registerUser(String phone, String name) throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName(name)
                .password("Password123!")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    private Map<String, String> buildSignedWebhookParams(Long txId, Long amountVnd) {
        long vnpAmount = amountVnd * 100L;
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", vnpayService.getTmnCode());
        params.put("vnp_Amount", String.valueOf(vnpAmount));
        params.put("vnp_TxnRef", String.valueOf(txId));
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "VNPAY_E2E_" + txId);
        params.put("vnp_OrderInfo", "Topup wallet transaction " + txId);
        params.put("vnp_PayDate", "20261126071500");

        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        for (String fieldName : fieldNames) {
            String val = params.get(fieldName);
            if (val != null && !val.isEmpty()) {
                String encodedVal = URLEncoder.encode(val, StandardCharsets.UTF_8);
                if (hashData.length() > 0) {
                    hashData.append('&');
                }
                hashData.append(fieldName).append('=').append(encodedVal);
            }
        }

        String secureHash = vnpayService.hmacSHA512(vnpayService.getHashSecret(), hashData.toString());
        params.put("vnp_SecureHash", secureHash);
        return params;
    }

    @Test
    @DisplayName("Phase C End-to-End Flow: Topup -> Lookup -> Dynamic QR -> P2P (idempotent notification) -> Withdraw -> History & Inbox -> Balance Reconciliation")
    void fullPhaseCFlow_endToEnd_succeedsAndReconciles() throws Exception {
        // 1. Register User A & User B, and set PIN for User A
        AuthResponse authA = registerUser("0917000001", "E2E User A");
        AuthResponse authB = registerUser("0917000002", "E2E User B");

        PinSetupRequest pinSetup = PinSetupRequest.builder()
                .pin("123456")
                .confirmPin("123456")
                .build();
        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinSetup)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        // 2. Top-up User A (500,000 VND) + signed VNPay webhook + async Kafka capture -> consumer
        TopupRequest topupRequest = TopupRequest.builder()
                .amount(500_000L)
                .description("E2E Topup")
                .build();
        MvcResult topupResult = mockMvc.perform(post("/api/v1/payments/topup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(topupRequest)))
                .andExpect(status().isOk())
                .andReturn();
        TopupResponse topupResponse = objectMapper.readValue(topupResult.getResponse().getContentAsString(), TopupResponse.class);
        Long topupTxId = topupResponse.getTransactionId();

        Map<String, String> webhookParams = buildSignedWebhookParams(topupTxId, 500_000L);
        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(webhookParams)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode", is("00")));

        ArgumentCaptor<String> topupPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, timeout(2000).times(1))
                .send(eq(TOPIC), eq(String.valueOf(topupTxId)), topupPayloadCaptor.capture());
        notificationConsumer.consume(topupPayloadCaptor.getValue());

        // 3. Lookup User B
        mockMvc.perform(get("/api/v1/users/lookup")
                        .param("phone", "0917000002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber", is("0917000002")))
                .andExpect(jsonPath("$.fullName", is("E2E User B")))
                .andExpect(jsonPath("$.canReceive", is(true)));

        // 4. User B generates DYNAMIC QR -> User A parses QR -> User A executes P2P Transfer (150,000 VND)
        QrGenerateRequest qrGenerateRequest = QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(150_000L)
                .description("Thanh toan QR E2E")
                .build();
        MvcResult qrGenResult = mockMvc.perform(post("/api/v1/qr/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authB.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(qrGenerateRequest)))
                .andExpect(status().isOk())
                .andReturn();
        QrGenerateResponse qrGenResponse = objectMapper.readValue(qrGenResult.getResponse().getContentAsString(), QrGenerateResponse.class);

        QrParseRequest qrParseRequest = QrParseRequest.builder()
                .qrContent(qrGenResponse.getQrContent())
                .build();
        mockMvc.perform(post("/api/v1/qr/parse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(qrParseRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("DYNAMIC")))
                .andExpect(jsonPath("$.receiverPhone", is("0917000002")))
                .andExpect(jsonPath("$.amount", is(150000)))
                .andExpect(jsonPath("$.isSelf", is(false)))
                .andExpect(jsonPath("$.canTransfer", is(true)));

        TransferRequest transferRequest = TransferRequest.builder()
                .receiverPhone("0917000002")
                .amount(150_000L)
                .pin("123456")
                .description("Thanh toan QR E2E")
                .build();
        MvcResult transferResult = mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken())
                        .header("Idempotency-Key", "e2e-p2p-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andReturn();
        TransferResponse transferResponse = objectMapper.readValue(transferResult.getResponse().getContentAsString(), TransferResponse.class);
        Long transferTxId = transferResponse.getTransactionId();

        ArgumentCaptor<String> p2pPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, timeout(2000).times(1))
                .send(eq(TOPIC), eq(String.valueOf(transferTxId)), p2pPayloadCaptor.capture());
        String p2pJson = p2pPayloadCaptor.getValue();
        // Consume TWICE for same P2P transaction to verify idempotency and zero duplicates under OSIV=false
        notificationConsumer.consume(p2pJson);
        notificationConsumer.consume(p2pJson);

        // 5. User A withdraws 100,000 VND
        WithdrawalRequest withdrawalRequest = WithdrawalRequest.builder()
                .bankCode("VCB")
                .bankAccountNumber("1234567890")
                .accountHolderName("E2E USER A")
                .amount(100_000L)
                .pin("123456")
                .description("E2E Withdraw")
                .build();
        MvcResult withdrawResult = mockMvc.perform(post("/api/v1/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken())
                        .header("Idempotency-Key", "e2e-wd-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawalRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.balanceAfter", is(250000)))
                .andReturn();
        WithdrawalResponse withdrawalResponse = objectMapper.readValue(withdrawResult.getResponse().getContentAsString(), WithdrawalResponse.class);
        Long withdrawTxId = withdrawalResponse.getTransactionId();

        ArgumentCaptor<String> withdrawPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, timeout(2000).times(1))
                .send(eq(TOPIC), eq(String.valueOf(withdrawTxId)), withdrawPayloadCaptor.capture());
        notificationConsumer.consume(withdrawPayloadCaptor.getValue());

        // 6. Verify User A transaction history & notification inbox + read-all
        mockMvc.perform(get("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[?(@.type=='TOPUP')].direction", org.hamcrest.Matchers.contains("IN")))
                .andExpect(jsonPath("$.content[?(@.type=='P2P_TRANSFER')].direction", org.hamcrest.Matchers.contains("OUT")))
                .andExpect(jsonPath("$.content[?(@.type=='WITHDRAW')].direction", org.hamcrest.Matchers.contains("OUT")));

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[*].type",
                        org.hamcrest.Matchers.containsInAnyOrder("TOPUP_SUCCESS", "TRANSFER_SENT", "WITHDRAW_SUCCESS")));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(3)));

        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount", is(3)));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(0)));

        // Verify User B received exactly 1 TRANSFER_RECEIVED notification (despite duplicate Kafka consume)
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authB.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].type", is("TRANSFER_RECEIVED")))
                .andExpect(jsonPath("$.content[0].read", is(false)));

        // 7. Reconcile final wallet balances
        Wallet walletA = walletRepository.findByUserId(authA.getUserId()).orElseThrow();
        Wallet walletB = walletRepository.findByUserId(authB.getUserId()).orElseThrow();
        Wallet systemWallet = walletRepository.findById(1L).orElseThrow();

        assertThat(walletA.getBalance()).isEqualTo(250_000L);
        assertThat(walletB.getBalance()).isEqualTo(150_000L);
        assertThat(systemWallet.getBalance()).isEqualTo(INITIAL_SYSTEM_BALANCE - 500_000L + 100_000L);
        assertThat(walletA.getBalance() + walletB.getBalance() + systemWallet.getBalance())
                .isEqualTo(INITIAL_SYSTEM_BALANCE);
    }
}
