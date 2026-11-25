package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.LoginRequest;
import com.meme.vwallet.dto.request.LogoutRequest;
import com.meme.vwallet.dto.request.RefreshTokenRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.WalletStatus;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
class AuthControllerIntegrationTest {

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
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            redisStore.put(key, val);
            return null;
        }).when(valueOperations).set(anyString(), any());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            redisStore.put(key, val);
            return null;
        }).when(valueOperations).set(anyString(), any(), any(Duration.class));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStore.get(key);
        }).when(valueOperations).get(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            return redisStore.putIfAbsent(key, val) == null;
        }).when(valueOperations).setIfAbsent(anyString(), any(), any(Duration.class));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
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
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> u.getId() != null && u.getId() > 3L)
                .forEach(u -> {
                    walletRepository.findByUserId(u.getId()).ifPresent(walletRepository::delete);
                    userRepository.delete(u);
                });
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - 201 Created, initializes active wallet with 0 balance and hasPin=false")
    void register_validUser_returns201CreatedAndCreatesWallet() throws Exception {
        String phone = "0933112233";
        RegisterRequest request = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("Nguyen Van Test")
                .password("StrongPassword123!")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId", notNullValue()))
                .andExpect(jsonPath("$.phoneNumber", is(phone)))
                .andExpect(jsonPath("$.fullName", is("Nguyen Van Test")))
                .andExpect(jsonPath("$.hasPin", is(false)))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);

        // Verify Database state
        User user = userRepository.findByPhoneNumber(phone).orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Nguyen Van Test");

        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualTo(0L);
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);

        // Verify Redis has active session
        assertThat(redisStore.get("auth:refresh:" + user.getId())).isNotNull();
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - 200 OK with valid tokens and credentials")
    void login_validCredentials_returns200OkAndTokens() throws Exception {
        String phone = "0933445566";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("User Login Test")
                .password("Password123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = LoginRequest.builder()
                .phoneNumber(phone)
                .password("Password123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.phoneNumber", is(phone)))
                .andExpect(jsonPath("$.hasPin", is(false)));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Refresh within Grace Period returns 200 OK and cached token pair")
    void refresh_withinGracePeriod_returns200Ok() throws Exception {
        String phone = "0933778899";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("User Refresh Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse initialAuth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);
        String oldRefreshToken = initialAuth.getRefreshToken();

        // First refresh call -> 200 OK with new tokens
        RefreshTokenRequest refreshReq = RefreshTokenRequest.builder()
                .refreshToken(oldRefreshToken)
                .build();

        MvcResult firstRefreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andReturn();

        AuthResponse rotatedAuth = objectMapper.readValue(firstRefreshResult.getResponse().getContentAsString(), AuthResponse.class);
        assertThat(rotatedAuth.getRefreshToken()).isNotEqualTo(oldRefreshToken);

        // Second refresh call with SAME oldRefreshToken within grace period -> 200 OK (cached response)
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", is(rotatedAuth.getAccessToken())))
                .andExpect(jsonPath("$.refreshToken", is(rotatedAuth.getRefreshToken())));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Post-Grace-Period reuse returns 401 TOKEN_REUSE_DETECTED and revokes session")
    void refresh_postGracePeriod_tokenReuseDetected_returns401() throws Exception {
        String phone = "0933990011";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("User Reuse Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse initialAuth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);
        String oldRefreshToken = initialAuth.getRefreshToken();
        Long userId = initialAuth.getUserId();

        // 1. Legitimate refresh
        RefreshTokenRequest refreshReq = RefreshTokenRequest.builder()
                .refreshToken(oldRefreshToken)
                .build();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk());

        // 2. Simulate post grace period: expire/remove the auth:rotated:* key
        String tokenId = jwtTokenProvider.getJtiFromToken(oldRefreshToken);
        redisStore.remove("auth:rotated:" + tokenId);

        // 3. Replay attack: send old refresh token again after grace period
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("TOKEN_REUSE_DETECTED")));

        // Active session should be revoked in containment
        assertThat(redisStore.get("auth:refresh:" + userId)).isNull();
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Locked user account returns 403 USER_LOCKED")
    void refresh_lockedUser_returns403Forbidden() throws Exception {
        String phone = "0933221100";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("User Locked Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

        // Lock user in DB
        User user = userRepository.findById(auth.getUserId()).orElseThrow();
        user.setStatus("LOCKED");
        userRepository.save(user);

        RefreshTokenRequest refreshReq = RefreshTokenRequest.builder()
                .refreshToken(auth.getRefreshToken())
                .build();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("USER_LOCKED")));
    }

    @Test
    @DisplayName("Token Type Confusion - Refresh Token sent to protected endpoint returns 401 INVALID_TOKEN_TYPE")
    void tokenTypeConfusion_refreshTokenToProtectedEndpoint_returns401() throws Exception {
        String phone = "0933556677";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("Token Confusion Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

        // Call protected endpoint with Refresh Token instead of Access Token
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getRefreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("INVALID_TOKEN_TYPE")));
    }

    @Test
    @DisplayName("Token Type Confusion - Access Token sent to /refresh returns 401 INVALID_TOKEN_TYPE")
    void tokenTypeConfusion_accessTokenToRefreshEndpoint_returns401() throws Exception {
        String phone = "0933887766";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("Access To Refresh Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

        RefreshTokenRequest requestWithAccessToken = RefreshTokenRequest.builder()
                .refreshToken(auth.getAccessToken())
                .build();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestWithAccessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("INVALID_TOKEN_TYPE")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout - Revokes token in Redis; subsequent request returns 401 TOKEN_REVOKED")
    void logout_blacklistsAccessToken_subsequentRequestReturns401() throws Exception {
        String phone = "0933441122";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("Logout Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);
        String accessToken = auth.getAccessToken();

        LogoutRequest logoutReq = LogoutRequest.builder()
                .refreshToken(auth.getRefreshToken())
                .build();

        // 1. Call logout
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("Đăng xuất thành công")));

        // 2. Subsequent request with blacklisted access token -> 401 TOKEN_REVOKED
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("TOKEN_REVOKED")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout - Succeeds without request body and revokes session")
    void logout_withoutRequestBody_returns200AndRevokesSession() throws Exception {
        String phone = "0933441123";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName("Logout Empty Body Test")
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        assertThat(redisStore.get("auth:refresh:" + auth.getUserId())).isNull();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is("TOKEN_REVOKED")));
    }
}
