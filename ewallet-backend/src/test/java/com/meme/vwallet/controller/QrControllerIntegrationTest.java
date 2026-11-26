package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.QrGenerateRequest;
import com.meme.vwallet.dto.request.QrParseRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.dto.response.QrGenerateResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.repository.WithdrawalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class QrControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${vwallet.qr.secret:dGhpc19pc19hX3Zlcnlfc2VjdXJlXzI1Nl9iaXRfcXJfZGV2X3NlY3JldF9rZXkh}")
    private String qrSecret;

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

        doAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null)
                .when(redisTemplate).delete(anyString());

        doAnswer(inv -> redisStore.containsKey(inv.getArgument(0)))
                .when(redisTemplate).hasKey(anyString());

        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        redisStore.clear();
        withdrawalRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> u.getId() != null && u.getId() > 3L)
                .forEach(u -> {
                    walletRepository.findByUserId(u.getId()).ifPresent(walletRepository::delete);
                    userRepository.delete(u);
                });
    }

    private AuthResponse registerUser(String phone, String fullName) throws Exception {
        RegisterRequest req = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName(fullName)
                .password("Password123!")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    @Test
    @DisplayName("POST /api/v1/qr/generate and /parse - Unauthenticated requests return 401")
    void qrEndpoints_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/qr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(QrGenerateRequest.builder().type("STATIC").build())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/qr/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QrParseRequest("vwallet://transfer?d=abc&sig=123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("User A generates DYNAMIC QR (150000, Tien ca phe), User B parses it -> 200 OK with isSelf=false and canTransfer=true")
    void generateAndParse_dynamicQr_endToEndSucceeds() throws Exception {
        AuthResponse userA = registerUser("0915000001", "QR Receiver A");
        AuthResponse userB = registerUser("0915000002", "QR Scanner B");

        QrGenerateRequest generateReq = QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(150_000L)
                .description("Tien ca phe")
                .build();

        MvcResult genResult = mockMvc.perform(post("/api/v1/qr/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrContent", startsWith("vwallet://transfer?d=")))
                .andExpect(jsonPath("$.type", is("DYNAMIC")))
                .andExpect(jsonPath("$.receiverPhone", is("0915000001")))
                .andExpect(jsonPath("$.receiverName", is("QR Receiver A")))
                .andExpect(jsonPath("$.amount", is(150000)))
                .andExpect(jsonPath("$.description", is("Tien ca phe")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        QrGenerateResponse genResponse = objectMapper.readValue(
                genResult.getResponse().getContentAsString(),
                QrGenerateResponse.class
        );

        mockMvc.perform(post("/api/v1/qr/parse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QrParseRequest(genResponse.getQrContent()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("DYNAMIC")))
                .andExpect(jsonPath("$.receiverPhone", is("0915000001")))
                .andExpect(jsonPath("$.receiverName", is("QR Receiver A")))
                .andExpect(jsonPath("$.amount", is(150000)))
                .andExpect(jsonPath("$.description", is("Tien ca phe")))
                .andExpect(jsonPath("$.isSelf", is(false)))
                .andExpect(jsonPath("$.canTransfer", is(true)))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("User A generates STATIC QR and parses own QR -> isSelf=true, canTransfer=false, null amount/expiresAt")
    void generateAndParse_staticSelfQr_returnsIsSelfTrueAndCanTransferFalse() throws Exception {
        AuthResponse userA = registerUser("0915000003", "Static QR Owner");

        QrGenerateRequest generateReq = QrGenerateRequest.builder()
                .type("STATIC")
                .amount(999_999L)
                .build();

        MvcResult genResult = mockMvc.perform(post("/api/v1/qr/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("STATIC")))
                .andExpect(jsonPath("$.amount").value(nullValue()))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andReturn();

        QrGenerateResponse genResponse = objectMapper.readValue(
                genResult.getResponse().getContentAsString(),
                QrGenerateResponse.class
        );
        assertThat(genResponse.getAmount()).isNull();
        assertThat(genResponse.getExpiresAt()).isNull();

        mockMvc.perform(post("/api/v1/qr/parse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QrParseRequest(genResponse.getQrContent()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("STATIC")))
                .andExpect(jsonPath("$.isSelf", is(true)))
                .andExpect(jsonPath("$.canTransfer", is(false)))
                .andExpect(jsonPath("$.amount").value(nullValue()))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/qr/parse - Tampered QR returns 400 INVALID_QR_CODE")
    void parse_tamperedQr_returns400InvalidQrCode() throws Exception {
        AuthResponse userA = registerUser("0915000004", "QR Tamper Target");
        AuthResponse userB = registerUser("0915000005", "QR Tamper Scanner");

        MvcResult genResult = mockMvc.perform(post("/api/v1/qr/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(QrGenerateRequest.builder()
                                .type("DYNAMIC")
                                .amount(100_000L)
                                .build())))
                .andExpect(status().isOk())
                .andReturn();

        String qrContent = objectMapper.readValue(
                genResult.getResponse().getContentAsString(),
                QrGenerateResponse.class
        ).getQrContent();

        String[] parts = URI.create(qrContent).getRawQuery().split("&");
        String d = parts[0].substring(2);
        String sig = parts[1].substring(4);
        char flipped = sig.charAt(0) == '0' ? '1' : '0';
        String tampered = "vwallet://transfer?d=" + d + "&sig=" + flipped + sig.substring(1);

        mockMvc.perform(post("/api/v1/qr/parse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QrParseRequest(tampered))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_QR_CODE")));
    }

    @Test
    @DisplayName("POST /api/v1/qr/parse - Expired QR returns 400 EXPIRED_QR_CODE")
    void parse_expiredQr_returns400ExpiredQrCode() throws Exception {
        AuthResponse scanner = registerUser("0915000006", "QR Expiry Scanner");

        String json = objectMapper.writeValueAsString(Map.of(
                "v", 1,
                "type", "DYNAMIC",
                "phone", "0901234567",
                "amount", 50_000L,
                "exp", Instant.now().minusSeconds(120).getEpochSecond()
        ));
        String d = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(qrSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(d.getBytes(StandardCharsets.UTF_8)));

        String expiredQr = "vwallet://transfer?d=" + d + "&sig=" + sig;

        mockMvc.perform(post("/api/v1/qr/parse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scanner.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QrParseRequest(expiredQr))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("EXPIRED_QR_CODE")));
    }

    @Test
    @DisplayName("POST /api/v1/qr/generate - Locked user returns 403 USER_LOCKED")
    void generate_lockedUser_returns403UserLocked() throws Exception {
        AuthResponse userA = registerUser("0915000007", "Locked QR User");
        User userEntity = userRepository.findById(userA.getUserId()).orElseThrow();
        userEntity.setStatus("LOCKED");
        userRepository.save(userEntity);

        mockMvc.perform(post("/api/v1/qr/generate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(QrGenerateRequest.builder().type("STATIC").build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("USER_LOCKED")));
    }

    @Test
    @DisplayName("POST /api/v1/qr/parse - SYSTEM_USER QR returns 404 USER_NOT_FOUND")
    void parse_systemUserQr_returns404UserNotFound() throws Exception {
        AuthResponse scanner = registerUser("0915000008", "QR System Scanner");

        String json = objectMapper.writeValueAsString(Map.of(
                "v", 1,
                "type", "STATIC",
                "phone", "0000000000"
        ));
        String d = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(qrSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(d.getBytes(StandardCharsets.UTF_8)));

        String systemQr = "vwallet://transfer?d=" + d + "&sig=" + sig;

        mockMvc.perform(post("/api/v1/qr/parse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scanner.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QrParseRequest(systemQr))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("USER_NOT_FOUND")));
    }
}
