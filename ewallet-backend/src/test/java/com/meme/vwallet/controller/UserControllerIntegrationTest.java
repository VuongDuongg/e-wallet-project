package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.PinSetupRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class UserControllerIntegrationTest {

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
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private MockMvc mockMvc;

    private final Map<String, Object> redisStore = new ConcurrentHashMap<>();
    private final Map<String, Long> redisTtlStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        redisStore.clear();
        redisTtlStore.clear();

        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            redisStore.put(key, val);
            return null;
        }).when(valueOperations).set(anyString(), any());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            Duration ttl = inv.getArgument(2);
            redisStore.put(key, val);
            redisTtlStore.put(key, ttl.getSeconds());
            return null;
        }).when(valueOperations).set(anyString(), any(), any(Duration.class));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStore.get(key);
        }).when(valueOperations).get(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            Duration ttl = inv.getArgument(2);
            boolean absent = redisStore.putIfAbsent(key, val) == null;
            if (absent) {
                redisTtlStore.put(key, ttl.getSeconds());
            }
            return absent;
        }).when(valueOperations).setIfAbsent(anyString(), any(), any(Duration.class));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            AtomicLong counter = (AtomicLong) redisStore.computeIfAbsent(key, k -> new AtomicLong(0L));
            return counter.incrementAndGet();
        }).when(valueOperations).increment(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (!redisStore.containsKey(key)) {
                return -2L;
            }
            return redisTtlStore.getOrDefault(key, -1L);
        }).when(redisTemplate).getExpire(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Duration ttl = inv.getArgument(1);
            if (redisStore.containsKey(key)) {
                redisTtlStore.put(key, ttl.getSeconds());
                return true;
            }
            return false;
        }).when(redisTemplate).expire(anyString(), any(Duration.class));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            redisTtlStore.remove(key);
            return redisStore.remove(key) != null;
        }).when(redisTemplate).delete(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStore.containsKey(key);
        }).when(redisTemplate).hasKey(anyString());

        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        redisStore.clear();
        redisTtlStore.clear();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> u.getId() != null && u.getId() > 3L)
                .forEach(u -> {
                    walletRepository.findByUserId(u.getId()).ifPresent(walletRepository::delete);
                    userRepository.delete(u);
                });
    }

    private AuthResponse registerUser(String phone, String name, String password) throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName(name)
                .password(password)
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);
    }

    @Test
    @DisplayName("POST /api/v1/users/pin - Set initial PIN successfully when hasPin is false")
    void setInitialPin_whenPinNotSet_returns200Ok() throws Exception {
        AuthResponse auth = registerUser("0912000001", "User Pin 1", "Password123!");

        PinSetupRequest pinRequest = PinSetupRequest.builder()
                .pin("123456")
                .confirmPin("123456")
                .build();

        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("Cài đặt mã PIN giao dịch thành công")));

        // Verify user in database now has pinHash set
        User user = userRepository.findById(auth.getUserId()).orElseThrow();
        assertThat(user.getPinHash()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("POST /api/v1/users/pin - Returns 400 Bad Request when PIN and Confirm PIN mismatch")
    void setInitialPin_confirmPinMismatch_returns400BadRequest() throws Exception {
        AuthResponse auth = registerUser("0912000002", "User Pin 2", "Password123!");

        PinSetupRequest pinRequest = PinSetupRequest.builder()
                .pin("123456")
                .confirmPin("654321")
                .build();

        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Mã PIN xác nhận không khớp")));
    }

    @Test
    @DisplayName("POST /api/v1/users/pin - Returns 400 PIN_ALREADY_SET when user already has PIN")
    void setInitialPin_whenAlreadySet_returns400PinAlreadySet() throws Exception {
        AuthResponse auth = registerUser("0912000003", "User Pin 3", "Password123!");

        PinSetupRequest pinRequest = PinSetupRequest.builder()
                .pin("123456")
                .confirmPin("123456")
                .build();

        // First PIN set -> 200 OK
        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinRequest)))
                .andExpect(status().isOk());

        // Second PIN set attempt -> 400 PIN_ALREADY_SET
        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("PIN_ALREADY_SET")));
    }

    @Test
    @DisplayName("GET /api/v1/users/me - Returns 200 OK with UserMeResponse reflecting hasPin state")
    void getMe_returnsUserMeResponse() throws Exception {
        AuthResponse auth = registerUser("0912000004", "User Me Test", "Password123!");

        // Initially hasPin=false
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(auth.getUserId().intValue())))
                .andExpect(jsonPath("$.phoneNumber", is("0912000004")))
                .andExpect(jsonPath("$.fullName", is("User Me Test")))
                .andExpect(jsonPath("$.hasPin", is(false)))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // Now set PIN
        PinSetupRequest pinRequest = PinSetupRequest.builder()
                .pin("654321")
                .confirmPin("654321")
                .build();

        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinRequest)))
                .andExpect(status().isOk());

        // After setting PIN, hasPin=true
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPin", is(true)));
    }

    @Test
    @DisplayName("Transaction IDOR - Non-participant receives 403 Forbidden; Participant receives 200 OK")
    void transactionIdor_nonParticipantReceives403_participantReceives200() throws Exception {
        // User A (Sender/Receiver participant)
        AuthResponse authUserA = registerUser("0912000005", "User A Participant", "Password123!");
        User userA = userRepository.findById(authUserA.getUserId()).orElseThrow();
        Wallet walletA = walletRepository.findByUserId(userA.getId()).orElseThrow();

        // User B (Partner participant)
        AuthResponse authUserB = registerUser("0912000006", "User B Partner", "Password123!");
        User userB = userRepository.findById(authUserB.getUserId()).orElseThrow();
        Wallet walletB = walletRepository.findByUserId(userB.getId()).orElseThrow();

        // User C (Attacker / Non-participant)
        AuthResponse authUserC = registerUser("0912000007", "User C Attacker", "Password123!");

        // Create transaction between walletA and walletB
        Transaction tx = Transaction.builder()
                .idempotencyKey("IDOR_TEST_" + UUID.randomUUID())
                .senderWallet(walletA)
                .receiverWallet(walletB)
                .amount(250_000L)
                .type(TransactionType.P2P_TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .description("IDOR Security Test Transaction")
                .build();
        tx = transactionRepository.save(tx);
        Long txId = tx.getId();

        // 1. Non-participant User C calls /api/v1/payments/transactions/{id} -> 403 Forbidden
        mockMvc.perform(get("/api/v1/payments/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authUserC.getAccessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Bạn không có quyền truy cập")));

        // Also test endpoint alias /api/v1/transactions/{id}
        mockMvc.perform(get("/api/v1/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authUserC.getAccessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Bạn không có quyền truy cập")));

        // 2. Participant User A calls /api/v1/payments/transactions/{id} -> 200 OK
        mockMvc.perform(get("/api/v1/payments/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authUserA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(txId.intValue())))
                .andExpect(jsonPath("$.amount", is(250000)))
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // 3. Participant User B calls /api/v1/transactions/{id} -> 200 OK
        mockMvc.perform(get("/api/v1/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authUserB.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(txId.intValue())))
                .andExpect(jsonPath("$.amount", is(250000)))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("GET /api/v1/users/lookup - Valid +84 phone returns 200 OK with normalized phone and canReceive=true")
    void lookupUser_validPlus84Phone_returns200Ok() throws Exception {
        AuthResponse caller = registerUser("0912000010", "Caller User", "Password123!");

        mockMvc.perform(get("/api/v1/users/lookup")
                        .param("phone", "+84987654321")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber", is("0987654321")))
                .andExpect(jsonPath("$.fullName", is("Tran Thi B")))
                .andExpect(jsonPath("$.isSelf", is(false)))
                .andExpect(jsonPath("$.canReceive", is(true)));
    }

    @Test
    @DisplayName("GET /api/v1/users/lookup - Self phone returns 200 OK with isSelf=true and canReceive=false")
    void lookupUser_selfPhone_returnsIsSelfTrueAndCanReceiveFalse() throws Exception {
        AuthResponse caller = registerUser("0912000011", "Self Lookup User", "Password123!");

        mockMvc.perform(get("/api/v1/users/lookup")
                        .param("phone", "0912000011")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber", is("0912000011")))
                .andExpect(jsonPath("$.fullName", is("Self Lookup User")))
                .andExpect(jsonPath("$.isSelf", is(true)))
                .andExpect(jsonPath("$.canReceive", is(false)));
    }

    @Test
    @DisplayName("GET /api/v1/users/lookup - System phone 0000000000 returns 400 INVALID_PHONE_FORMAT")
    void lookupUser_systemPhone0000000000_returns400InvalidPhoneFormat() throws Exception {
        AuthResponse caller = registerUser("0912000012", "Caller System Phone", "Password123!");

        mockMvc.perform(get("/api/v1/users/lookup")
                        .param("phone", "0000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.getAccessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_PHONE_FORMAT")));
    }

    @Test
    @DisplayName("GET /api/v1/users/lookup - Nonexistent phone returns 404 USER_NOT_FOUND")
    void lookupUser_nonexistentPhone_returns404UserNotFound() throws Exception {
        AuthResponse caller = registerUser("0912000013", "Caller Missing Target", "Password123!");

        mockMvc.perform(get("/api/v1/users/lookup")
                        .param("phone", "0911999888")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.getAccessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("USER_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /api/v1/users/lookup - Exceeding 30 requests/minute returns 429 RATE_LIMIT_EXCEEDED")
    void lookupUser_rateLimitExceeded_returns429() throws Exception {
        AuthResponse caller = registerUser("0912000014", "Caller Rate Limit", "Password123!");
        redisStore.put("ratelimit:lookup:" + caller.getUserId(), new AtomicLong(30L));
        redisTtlStore.put("ratelimit:lookup:" + caller.getUserId(), 45L);

        mockMvc.perform(get("/api/v1/users/lookup")
                        .param("phone", "0987654321")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.getAccessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode", is("RATE_LIMIT_EXCEEDED")));
    }

    @Test
    @DisplayName("GET /api/v1/users/lookup - Missing phone query parameter returns 400 BAD_REQUEST")
    void lookupUser_missingPhoneParam_returns400BadRequest() throws Exception {
        AuthResponse caller = registerUser("0912000015", "Caller Missing Param", "Password123!");

        mockMvc.perform(get("/api/v1/users/lookup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.getAccessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }
}
