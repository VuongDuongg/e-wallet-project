package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.PinSetupRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.request.WithdrawalRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.dto.response.WithdrawalResponse;
import com.meme.vwallet.entity.LedgerEntry;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.Withdrawal;
import com.meme.vwallet.entity.enums.EntryType;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.repository.WithdrawalRepository;
import org.apache.commons.codec.digest.DigestUtils;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
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
class WithdrawalControllerIntegrationTest {

    private static final long INITIAL_SYSTEM_BALANCE = 1_000_000_000_000L;

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

    private AuthResponse registerUserWithPinAndBalance(String phone, String name, String pin, long balance) throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName(name)
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

        PinSetupRequest pinSetup = PinSetupRequest.builder()
                .pin(pin)
                .confirmPin(pin)
                .build();

        mockMvc.perform(post("/api/v1/users/pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinSetup)))
                .andExpect(status().isOk());

        Wallet wallet = walletRepository.findByUserId(auth.getUserId()).orElseThrow();
        wallet.setBalance(balance);
        walletRepository.save(wallet);

        return auth;
    }

    @Test
    @DisplayName("POST /api/v1/withdrawals - Unauthenticated request returns 401")
    void withdraw_unauthenticated_returns401() throws Exception {
        WithdrawalRequest req = WithdrawalRequest.builder()
                .bankCode("VCB")
                .bankAccountNumber("1234567890")
                .accountHolderName("NGUYEN VAN A")
                .amount(200_000L)
                .pin("123456")
                .build();

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", "unauth-wd-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/withdrawals - Deducts user wallet, credits SYSTEM_WALLET, writes 2 ledger entries + 1 withdrawal, and idempotent retry returns cached 200 OK")
    void withdraw_authenticatedAndRetry_settlesDoubleEntryOnce() throws Exception {
        AuthResponse auth = registerUserWithPinAndBalance("0914000001", "Withdraw User", "123456", 1_000_000L);
        Wallet systemBefore = walletRepository.findById(1L).orElseThrow();
        long initialSystemBalance = systemBefore.getBalance();

        String longIdempotencyKey = "wd-client-key-" + "z".repeat(100);
        WithdrawalRequest req = WithdrawalRequest.builder()
                .bankCode("VCB")
                .bankAccountNumber("1234567890")
                .accountHolderName("WITHDRAW USER")
                .amount(200_000L)
                .pin("123456")
                .description("Cash out to Vietcombank")
                .build();

        MvcResult firstResult = mockMvc.perform(post("/api/v1/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .header("Idempotency-Key", longIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.bankCode", is("VCB")))
                .andExpect(jsonPath("$.bankAccountNumber", is("1234567890")))
                .andExpect(jsonPath("$.accountHolderName", is("WITHDRAW USER")))
                .andExpect(jsonPath("$.amount", is(200000)))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.bankRef", startsWith("MOCK_BANK_")))
                .andExpect(jsonPath("$.balanceAfter", is(800000)))
                .andReturn();

        WithdrawalResponse firstResponse = objectMapper.readValue(
                firstResult.getResponse().getContentAsString(),
                WithdrawalResponse.class
        );

        Wallet userWalletAfter = walletRepository.findByUserId(auth.getUserId()).orElseThrow();
        Wallet systemWalletAfter = walletRepository.findById(1L).orElseThrow();
        assertThat(userWalletAfter.getBalance()).isEqualTo(800_000L);
        assertThat(systemWalletAfter.getBalance()).isEqualTo(initialSystemBalance + 200_000L);

        Transaction tx = transactionRepository.findById(firstResponse.getTransactionId()).orElseThrow();
        assertThat(tx.getType()).isEqualTo(TransactionType.WITHDRAW);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getIdempotencyKey()).hasSize(64);
        assertThat(tx.getIdempotencyKey()).isEqualTo(DigestUtils.sha256Hex("WD:" + auth.getUserId() + ":" + longIdempotencyKey));

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getWallet().getId()).isEqualTo(userWalletAfter.getId());
            assertThat(e.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(e.getAmount()).isEqualTo(200_000L);
            assertThat(e.getBalanceAfter()).isEqualTo(800_000L);
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getWallet().getId()).isEqualTo(1L);
            assertThat(e.getEntryType()).isEqualTo(EntryType.CREDIT);
            assertThat(e.getAmount()).isEqualTo(200_000L);
            assertThat(e.getBalanceAfter()).isEqualTo(initialSystemBalance + 200_000L);
        });

        List<Withdrawal> withdrawals = withdrawalRepository.findAll();
        assertThat(withdrawals).hasSize(1);
        assertThat(withdrawals.get(0).getId()).isEqualTo(firstResponse.getWithdrawalId());
        assertThat(withdrawals.get(0).getBankCode()).isEqualTo("VCB");
        assertThat(withdrawals.get(0).getAmount()).isEqualTo(200_000L);

        // Retry with the exact same Idempotency-Key -> cached 200 OK without second deduction
        MvcResult retryResult = mockMvc.perform(post("/api/v1/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .header("Idempotency-Key", longIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        WithdrawalResponse retryResponse = objectMapper.readValue(
                retryResult.getResponse().getContentAsString(),
                WithdrawalResponse.class
        );
        assertThat(retryResponse.getWithdrawalId()).isEqualTo(firstResponse.getWithdrawalId());
        assertThat(retryResponse.getTransactionId()).isEqualTo(firstResponse.getTransactionId());
        assertThat(walletRepository.findByUserId(auth.getUserId()).orElseThrow().getBalance()).isEqualTo(800_000L);
        assertThat(walletRepository.findById(1L).orElseThrow().getBalance()).isEqualTo(initialSystemBalance + 200_000L);
        assertThat(withdrawalRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /api/v1/withdrawals - Wrong PIN returns 400 INVALID_PIN and releases lock so retry with valid PIN succeeds")
    void withdraw_wrongPinThenRetryWithValidPin_succeeds() throws Exception {
        AuthResponse auth = registerUserWithPinAndBalance("0914000002", "Retry Pin User", "123456", 500_000L);
        String key = "wd-retry-pin-1";

        WithdrawalRequest wrongPinReq = WithdrawalRequest.builder()
                .bankCode("TCB")
                .bankAccountNumber("9876543210")
                .accountHolderName("RETRY PIN USER")
                .amount(100_000L)
                .pin("999999")
                .build();

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongPinReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_PIN")));

        WithdrawalRequest validReq = WithdrawalRequest.builder()
                .bankCode("TCB")
                .bankAccountNumber("9876543210")
                .accountHolderName("RETRY PIN USER")
                .amount(100_000L)
                .pin("123456")
                .build();

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter", is(400000)));
    }
}
