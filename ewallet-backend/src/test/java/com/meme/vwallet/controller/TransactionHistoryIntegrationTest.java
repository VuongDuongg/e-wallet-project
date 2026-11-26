package com.meme.vwallet.controller;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
class TransactionHistoryIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        redisStore.clear();
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

    private Transaction saveTransaction(Wallet sender, Wallet receiver, long amount,
                                        TransactionType type, TransactionStatus status,
                                        String description, String externalRef, Instant createdAt) {
        Transaction tx = Transaction.builder()
                .idempotencyKey("HIST_" + UUID.randomUUID())
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(amount)
                .type(type)
                .status(status)
                .description(description)
                .externalRef(externalRef)
                .build();
        tx = transactionRepository.save(tx);
        jdbcTemplate.update(
                "UPDATE transactions SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                tx.getId()
        );
        return tx;
    }

    @Test
    @DisplayName("GET /api/v1/transactions - Unauthenticated request returns 401")
    void getHistory_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/transactions - Isolates user transactions, filters by direction/type/status/date, and paginates with OSIV=false")
    void getHistory_authenticatedUser_filtersAndPaginatesWithoutLazyInitException() throws Exception {
        AuthResponse authA = registerUser("0913000001", "User Alpha", "Password123!");
        AuthResponse authB = registerUser("0913000002", "User Beta", "Password123!");
        AuthResponse authC = registerUser("0913000003", "User Gamma", "Password123!");

        Wallet systemWallet = walletRepository.findById(1L).orElseThrow();
        Wallet walletA = walletRepository.findByUserId(authA.getUserId()).orElseThrow();
        Wallet walletB = walletRepository.findByUserId(authB.getUserId()).orElseThrow();
        Wallet walletC = walletRepository.findByUserId(authC.getUserId()).orElseThrow();

        Instant t1 = Instant.parse("2026-11-20T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-21T10:00:00Z");
        Instant t3 = Instant.parse("2026-11-22T10:00:00Z");
        Instant t4 = Instant.parse("2026-11-23T10:00:00Z");

        // User A transactions (4 total)
        Transaction aTopup = saveTransaction(systemWallet, walletA, 500_000L,
                TransactionType.TOPUP, TransactionStatus.COMPLETED,
                "Nap tien", "VNPAY_1", t1);

        Transaction aSentToB = saveTransaction(walletA, walletB, 120_000L,
                TransactionType.P2P_TRANSFER, TransactionStatus.COMPLETED,
                "Chuyen B", null, t2);

        Transaction bSentToA = saveTransaction(walletB, walletA, 80_000L,
                TransactionType.P2P_TRANSFER, TransactionStatus.COMPLETED,
                "B tra A", null, t3);

        Transaction aWithdrawFailed = saveTransaction(walletA, systemWallet, 200_000L,
                TransactionType.WITHDRAW, TransactionStatus.FAILED,
                "Rut loi", "BANK_ERR", t4);

        // Unrelated transaction between B and C (must NEVER appear for A)
        Transaction bToC = saveTransaction(walletB, walletC, 999_000L,
                TransactionType.P2P_TRANSFER, TransactionStatus.COMPLETED,
                "B to C secret", null, t4);

        // 1. Default ALL query with page=0, size=2 -> newest 2 of User A, never B<->C
        mockMvc.perform(get("/api/v1/transactions")
                        .param("page", "0")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(4)))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(false)))
                .andExpect(jsonPath("$.content[0].transactionId", is(aWithdrawFailed.getId().intValue())))
                .andExpect(jsonPath("$.content[0].direction", is("OUT")))
                .andExpect(jsonPath("$.content[0].counterpartyPhone", is("BANK")))
                .andExpect(jsonPath("$.content[1].transactionId", is(bSentToA.getId().intValue())))
                .andExpect(jsonPath("$.content[1].direction", is("IN")))
                .andExpect(jsonPath("$.content[1].counterpartyPhone", is("0913000002")))
                .andExpect(jsonPath("$.content[1].counterpartyName", is("User Beta")))
                .andExpect(jsonPath("$.content[*].transactionId", not(hasItem(bToC.getId().intValue()))));

        // 2. Page 1, size=2 -> older 2 of User A
        mockMvc.perform(get("/api/v1/transactions")
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.totalElements", is(4)))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.first", is(false)))
                .andExpect(jsonPath("$.last", is(true)))
                .andExpect(jsonPath("$.content[0].transactionId", is(aSentToB.getId().intValue())))
                .andExpect(jsonPath("$.content[0].direction", is("OUT")))
                .andExpect(jsonPath("$.content[0].counterpartyPhone", is("0913000002")))
                .andExpect(jsonPath("$.content[1].transactionId", is(aTopup.getId().intValue())))
                .andExpect(jsonPath("$.content[1].direction", is("IN")))
                .andExpect(jsonPath("$.content[1].counterpartyPhone", is("SYSTEM")))
                .andExpect(jsonPath("$.content[1].counterpartyName", is("VNPay Top-up")));

        // 3. Filter direction=IN
        mockMvc.perform(get("/api/v1/transactions")
                        .param("direction", "IN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].transactionId", is(bSentToA.getId().intValue())))
                .andExpect(jsonPath("$.content[1].transactionId", is(aTopup.getId().intValue())));

        // 4. Filter direction=OUT & type=P2P_TRANSFER & status=COMPLETED
        mockMvc.perform(get("/api/v1/transactions")
                        .param("direction", "OUT")
                        .param("type", "P2P_TRANSFER")
                        .param("status", "COMPLETED")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionId", is(aSentToB.getId().intValue())))
                .andExpect(jsonPath("$.content[0].counterpartyPhone", is("0913000002")));

        // 5. Filter by from/to date window [t2, t3]
        mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "2026-11-21T00:00:00Z")
                        .param("to", "2026-11-22T23:59:59Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].transactionId", is(bSentToA.getId().intValue())))
                .andExpect(jsonPath("$.content[1].transactionId", is(aSentToB.getId().intValue())));
    }

    @Test
    @DisplayName("GET /api/v1/transactions - Invalid date range (from > to) or invalid type enum returns 400 BAD_REQUEST")
    void getHistory_invalidDateRangeOrEnum_returns400BadRequest() throws Exception {
        AuthResponse auth = registerUser("0913000004", "History Validation User", "Password123!");

        mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "2026-11-25T00:00:00Z")
                        .param("to", "2026-11-20T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("type", "INVALID")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));
    }
}
