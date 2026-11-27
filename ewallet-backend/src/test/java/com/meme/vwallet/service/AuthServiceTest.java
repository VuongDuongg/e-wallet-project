package com.meme.vwallet.service;

import com.meme.vwallet.service.auth.AuthService;

import com.meme.vwallet.dto.request.LoginRequest;
import com.meme.vwallet.dto.request.RefreshTokenRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.ConflictException;
import com.meme.vwallet.exception.InvalidTokenTypeException;
import com.meme.vwallet.exception.TokenReuseException;
import com.meme.vwallet.exception.UserAlreadyExistsException;
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.security.JwtTokenProvider;
import com.meme.vwallet.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private ObjectMapper objectMapper;
    private AuthService authService;

    private static final long ACCESS_EXPIRATION_SECONDS = 900L;
    private static final long REFRESH_EXPIRATION_SECONDS = 604800L;
    private static final long REFRESH_GRACE_PERIOD_SECONDS = 30L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(ACCESS_EXPIRATION_SECONDS);
        lenient().when(jwtTokenProvider.getRefreshTokenExpirationSeconds()).thenReturn(REFRESH_EXPIRATION_SECONDS);

        authService = new AuthService(
                userRepository,
                walletRepository,
                passwordEncoder,
                jwtTokenProvider,
                redisTemplate,
                objectMapper,
                REFRESH_GRACE_PERIOD_SECONDS
        );
    }

    @Nested
    @DisplayName("register tests")
    class RegisterTests {

        @Test
        @DisplayName("register - successful: normalizes phone, encodes password, creates User & Wallet, saves refresh token in Redis")
        void register_successful_createsUserAndWalletAndSetsRedis() {
            RegisterRequest request = RegisterRequest.builder()
                    .phoneNumber("+84901234567")
                    .fullName("Nguyen Van A")
                    .password("Password123")
                    .build();

            when(userRepository.existsByPhoneNumber("0901234567")).thenReturn(false);
            when(passwordEncoder.encode("Password123")).thenReturn("encodedPasswordHash");

            User savedUser = User.builder()
                    .id(1L)
                    .phoneNumber("0901234567")
                    .fullName("Nguyen Van A")
                    .passwordHash("encodedPasswordHash")
                    .status("ACTIVE")
                    .build();
            when(userRepository.save(any(User.class))).thenReturn(savedUser);

            when(jwtTokenProvider.generateAccessToken(1L, "0901234567", "Nguyen Van A"))
                    .thenReturn("access-token-123");
            when(jwtTokenProvider.generateRefreshToken(eq(1L), anyString()))
                    .thenReturn("refresh-token-456");

            AuthResponse response = authService.register(request);

            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getPhoneNumber()).isEqualTo("0901234567");
            assertThat(response.getFullName()).isEqualTo("Nguyen Van A");
            assertThat(response.getHasPin()).isFalse();
            assertThat(response.getAccessToken()).isEqualTo("access-token-123");
            assertThat(response.getRefreshToken()).isEqualTo("refresh-token-456");
            assertThat(response.getTokenType()).isEqualTo("Bearer");
            assertThat(response.getExpiresIn()).isEqualTo(ACCESS_EXPIRATION_SECONDS);

            // Verify User creation
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User createdUser = userCaptor.getValue();
            assertThat(createdUser.getPhoneNumber()).isEqualTo("0901234567");
            assertThat(createdUser.getFullName()).isEqualTo("Nguyen Van A");
            assertThat(createdUser.getPasswordHash()).isEqualTo("encodedPasswordHash");
            assertThat(createdUser.getStatus()).isEqualTo("ACTIVE");

            // Verify Wallet creation
            ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
            verify(walletRepository).save(walletCaptor.capture());
            Wallet createdWallet = walletCaptor.getValue();
            assertThat(createdWallet.getUser()).isEqualTo(savedUser);
            assertThat(createdWallet.getBalance()).isEqualTo(0L);
            assertThat(createdWallet.getCurrency()).isEqualTo("VND");
            assertThat(createdWallet.getVersion()).isEqualTo(0L);
            assertThat(createdWallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);

            // Verify Redis refresh token saved
            verify(valueOperations).set(eq("auth:refresh:1"), anyString(), eq(Duration.ofSeconds(REFRESH_EXPIRATION_SECONDS)));
        }

        @Test
        @DisplayName("register - phone already exists: throws UserAlreadyExistsException and skips creation")
        void register_phoneAlreadyExists_throwsUserAlreadyExistsException() {
            RegisterRequest request = RegisterRequest.builder()
                    .phoneNumber("0901234567")
                    .fullName("Nguyen Van A")
                    .password("Password123")
                    .build();

            when(userRepository.existsByPhoneNumber("0901234567")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(UserAlreadyExistsException.class);

            verify(userRepository, never()).save(any());
            verify(walletRepository, never()).save(any());
            verify(jwtTokenProvider, never()).generateAccessToken(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("login tests")
    class LoginTests {

        @Test
        @DisplayName("login - successful: canonicalizes phone, checks password, generates tokens and updates Redis")
        void login_successful_generatesTokensAndSetsRedis() {
            LoginRequest request = LoginRequest.builder()
                    .phoneNumber("+84901234567")
                    .password("Password123")
                    .build();

            User user = User.builder()
                    .id(1L)
                    .phoneNumber("0901234567")
                    .fullName("Nguyen Van A")
                    .passwordHash("encodedPasswordHash")
                    .pinHash("encodedPin")
                    .status("ACTIVE")
                    .build();

            when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Password123", "encodedPasswordHash")).thenReturn(true);
            when(jwtTokenProvider.generateAccessToken(1L, "0901234567", "Nguyen Van A")).thenReturn("access-token-abc");
            when(jwtTokenProvider.generateRefreshToken(eq(1L), anyString())).thenReturn("refresh-token-xyz");

            AuthResponse response = authService.login(request);

            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getPhoneNumber()).isEqualTo("0901234567");
            assertThat(response.getFullName()).isEqualTo("Nguyen Van A");
            assertThat(response.getHasPin()).isTrue();
            assertThat(response.getAccessToken()).isEqualTo("access-token-abc");
            assertThat(response.getRefreshToken()).isEqualTo("refresh-token-xyz");
            assertThat(response.getExpiresIn()).isEqualTo(ACCESS_EXPIRATION_SECONDS);

            verify(valueOperations).set(eq("auth:refresh:1"), anyString(), eq(Duration.ofSeconds(REFRESH_EXPIRATION_SECONDS)));
        }

        @Test
        @DisplayName("login - user not found: throws BadCredentialsException")
        void login_userNotFound_throwsBadCredentialsException() {
            LoginRequest request = LoginRequest.builder()
                    .phoneNumber("0901234567")
                    .password("Password123")
                    .build();

            when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Số điện thoại hoặc mật khẩu không chính xác");
        }

        @Test
        @DisplayName("login - wrong password: throws BadCredentialsException")
        void login_wrongPassword_throwsBadCredentialsException() {
            LoginRequest request = LoginRequest.builder()
                    .phoneNumber("0901234567")
                    .password("WrongPassword")
                    .build();

            User user = User.builder()
                    .id(1L)
                    .phoneNumber("0901234567")
                    .passwordHash("encodedPasswordHash")
                    .status("ACTIVE")
                    .build();

            when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("WrongPassword", "encodedPasswordHash")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Số điện thoại hoặc mật khẩu không chính xác");
        }

        @Test
        @DisplayName("login - user locked/inactive: throws UserLockedException")
        void login_inactiveOrLockedUser_throwsUserLockedException() {
            LoginRequest request = LoginRequest.builder()
                    .phoneNumber("0901234567")
                    .password("Password123")
                    .build();

            User user = User.builder()
                    .id(1L)
                    .phoneNumber("0901234567")
                    .passwordHash("encodedPasswordHash")
                    .status("LOCKED")
                    .build();

            when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Password123", "encodedPasswordHash")).thenReturn(true);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(UserLockedException.class)
                    .hasMessageContaining("Tài khoản của bạn đã bị khóa");
        }
    }

    @Nested
    @DisplayName("refreshToken tests")
    class RefreshTokenTests {

        private final String validRefreshToken = "sample.refresh.jwt";
        private final String oldTokenId = "old-token-id-123";

        @Test
        @DisplayName("refreshToken - Happy path: rotates token, saves old token in auth:rotated with 30s TTL, updates auth:refresh")
        void refreshToken_happyPath_rotatesTokenAndCachesRotatedToken() {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            // Fast-check rotated cache -> miss
            when(valueOperations.get("auth:rotated:" + oldTokenId)).thenReturn(null);

            // Acquire lock -> success
            when(valueOperations.setIfAbsent(eq("auth:refresh_lock:1"), anyString(), eq(Duration.ofSeconds(5))))
                    .thenAnswer(inv -> {
                        String token = inv.getArgument(1);
                        when(valueOperations.get("auth:refresh_lock:1")).thenReturn(token);
                        return true;
                    });

            // Double check rotated cache inside lock -> miss
            // (Mock returns null for "auth:rotated:old-token-id-123")

            User user = User.builder()
                    .id(1L)
                    .phoneNumber("0901234567")
                    .fullName("Nguyen Van A")
                    .status("ACTIVE")
                    .build();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // Active token check in Redis matches oldTokenId
            when(valueOperations.get("auth:refresh:1")).thenReturn(oldTokenId);

            when(jwtTokenProvider.generateAccessToken(1L, "0901234567", "Nguyen Van A")).thenReturn("new-access-token");
            when(jwtTokenProvider.generateRefreshToken(eq(1L), anyString())).thenReturn("new-refresh-token");

            AuthResponse response = authService.refreshToken(request);

            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("new-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");

            // Verify active refresh token updated
            verify(valueOperations).set(eq("auth:refresh:1"), anyString(), eq(Duration.ofSeconds(REFRESH_EXPIRATION_SECONDS)));

            // Verify grace period rotated cache stored with 30s TTL
            verify(valueOperations).set(eq("auth:rotated:" + oldTokenId), anyString(), eq(Duration.ofSeconds(REFRESH_GRACE_PERIOD_SECONDS)));

            // Verify lock released
            verify(redisTemplate).delete("auth:refresh_lock:1");
        }

        @Test
        @DisplayName("refreshToken - Grace Period hit: returns cached AuthResponse without acquiring lock")
        void refreshToken_gracePeriodHit_returnsCachedResponseImmediately() throws Exception {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            AuthResponse cachedResponse = AuthResponse.builder()
                    .userId(1L)
                    .phoneNumber("0901234567")
                    .fullName("Nguyen Van A")
                    .accessToken("cached-access-token")
                    .refreshToken("cached-refresh-token")
                    .build();
            String cachedJson = objectMapper.writeValueAsString(cachedResponse);

            // Fast-check rotated cache -> hit
            when(valueOperations.get("auth:rotated:" + oldTokenId)).thenReturn(cachedJson);

            AuthResponse response = authService.refreshToken(request);

            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("cached-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("cached-refresh-token");

            // Lock never acquired
            verify(valueOperations, never()).setIfAbsent(anyString(), any(), any(Duration.class));
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("refreshToken - Token Reuse Breach: unknown/stale token triggers deletion of auth:refresh and throws TokenReuseException")
        void refreshToken_tokenReuseDetected_revokesActiveSessionAndThrowsTokenReuseException() {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            when(valueOperations.get("auth:rotated:" + oldTokenId)).thenReturn(null);
            when(valueOperations.setIfAbsent(eq("auth:refresh_lock:1"), anyString(), eq(Duration.ofSeconds(5))))
                    .thenAnswer(inv -> {
                        String token = inv.getArgument(1);
                        when(valueOperations.get("auth:refresh_lock:1")).thenReturn(token);
                        return true;
                    });

            User user = User.builder().id(1L).phoneNumber("0901234567").fullName("Nguyen Van A").status("ACTIVE").build();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // Active token in Redis differs from current request tokenId -> reuse/breach!
            when(valueOperations.get("auth:refresh:1")).thenReturn("different-current-tokenId-999");

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(TokenReuseException.class)
                    .hasMessageContaining("Phát hiện token đã qua sử dụng hoặc phiên đăng nhập không hợp lệ");

            // Containment: active session must be revoked
            verify(redisTemplate).delete("auth:refresh:1");
            // Lock must be released
            verify(redisTemplate).delete("auth:refresh_lock:1");
        }

        @Test
        @DisplayName("refreshToken - Lock timeout: 3 retries without finding rotated token throws 409 ConflictException")
        void refreshToken_lockTimeoutAfterRetries_throwsConflictException() {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            // Fast-check miss
            when(valueOperations.get("auth:rotated:" + oldTokenId)).thenReturn(null);
            // Lock failure
            when(valueOperations.setIfAbsent(eq("auth:refresh_lock:1"), anyString(), eq(Duration.ofSeconds(5))))
                    .thenReturn(false);

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Yêu cầu đang được xử lý, vui lòng thử lại");

            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("refreshToken - Lock contention: rotated token cached during retry resolves cleanly")
        void refreshToken_lockContention_resolvedDuringRetryGracePeriod() throws Exception {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            AuthResponse cachedResponse = AuthResponse.builder()
                    .userId(1L)
                    .accessToken("concurrent-access-token")
                    .refreshToken("concurrent-refresh-token")
                    .build();
            String cachedJson = objectMapper.writeValueAsString(cachedResponse);

            // 1st get: null (fast-check)
            // 2nd get: null (1st retry)
            // 3rd get: cachedJson (2nd retry)
            when(valueOperations.get("auth:rotated:" + oldTokenId))
                    .thenReturn(null)
                    .thenReturn(null)
                    .thenReturn(cachedJson);

            when(valueOperations.setIfAbsent(eq("auth:refresh_lock:1"), anyString(), eq(Duration.ofSeconds(5))))
                    .thenReturn(false);

            AuthResponse response = authService.refreshToken(request);

            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("concurrent-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("concurrent-refresh-token");
        }

        @Test
        @DisplayName("refreshToken - User locked/inactive: throws UserLockedException")
        void refreshToken_userLocked_throwsUserLockedException() {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            when(valueOperations.get("auth:rotated:" + oldTokenId)).thenReturn(null);
            when(valueOperations.setIfAbsent(eq("auth:refresh_lock:1"), anyString(), eq(Duration.ofSeconds(5))))
                    .thenAnswer(inv -> {
                        String token = inv.getArgument(1);
                        when(valueOperations.get("auth:refresh_lock:1")).thenReturn(token);
                        return true;
                    });

            User user = User.builder().id(1L).phoneNumber("0901234567").status("LOCKED").build();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(UserLockedException.class)
                    .hasMessageContaining("Tài khoản của bạn đã bị khóa");

            verify(redisTemplate).delete("auth:refresh_lock:1");
        }

        @Test
        @DisplayName("refreshToken - Lock expired and taken by another process: does not release lock")
        void refreshToken_lockExpired_doesNotReleaseOtherProcessLock() {
            RefreshTokenRequest request = new RefreshTokenRequest(validRefreshToken);

            when(jwtTokenProvider.validateToken(validRefreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenType(validRefreshToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
            when(jwtTokenProvider.getUserIdFromToken(validRefreshToken)).thenReturn(1L);
            when(jwtTokenProvider.getJtiFromToken(validRefreshToken)).thenReturn(oldTokenId);

            when(valueOperations.get("auth:rotated:" + oldTokenId)).thenReturn(null);
            when(valueOperations.setIfAbsent(eq("auth:refresh_lock:1"), anyString(), eq(Duration.ofSeconds(5))))
                    .thenReturn(true);

            User user = User.builder().id(1L).phoneNumber("0901234567").fullName("Nguyen Van A").status("ACTIVE").build();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(valueOperations.get("auth:refresh:1")).thenReturn(oldTokenId);

            when(jwtTokenProvider.generateAccessToken(1L, "0901234567", "Nguyen Van A")).thenReturn("new-access-token");
            when(jwtTokenProvider.generateRefreshToken(eq(1L), anyString())).thenReturn("new-refresh-token");

            // Lock expired during processing; another worker took the lock with another token
            when(valueOperations.get("auth:refresh_lock:1")).thenReturn("different-process-lock-token");

            AuthResponse response = authService.refreshToken(request);

            assertThat(response).isNotNull();
            verify(redisTemplate, never()).delete("auth:refresh_lock:1");
        }

        @Test
        @DisplayName("refreshToken - Invalid token type (e.g. ACCESS token): throws InvalidTokenTypeException")
        void refreshToken_invalidTokenType_throwsInvalidTokenTypeException() {
            RefreshTokenRequest request = new RefreshTokenRequest("access.jwt.token");

            when(jwtTokenProvider.validateToken("access.jwt.token")).thenReturn(true);
            when(jwtTokenProvider.getTokenType("access.jwt.token")).thenReturn(JwtTokenProvider.TOKEN_TYPE_ACCESS);

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(InvalidTokenTypeException.class)
                    .hasMessageContaining("Token type không hợp lệ, yêu cầu Refresh Token");
        }

        @Test
        @DisplayName("refreshToken - Invalid or expired token: throws InvalidTokenTypeException")
        void refreshToken_invalidOrExpiredJwt_throwsInvalidTokenTypeException() {
            RefreshTokenRequest request = new RefreshTokenRequest("malformed.jwt.token");

            when(jwtTokenProvider.validateToken("malformed.jwt.token")).thenReturn(false);

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(InvalidTokenTypeException.class);
        }
    }

    @Nested
    @DisplayName("logout tests")
    class LogoutTests {

        @Test
        @DisplayName("logout - with UserPrincipal: blacklists access token with remaining TTL and deletes refresh token")
        void logout_withUserPrincipal_blacklistsAccessTokenAndDeletesRefreshToken() {
            Instant expiresAt = Instant.now().plusSeconds(300);
            UserPrincipal principal = new UserPrincipal(1L, "0901234567", "Nguyen Van A", "jti-access-999", expiresAt);

            authService.logout(principal, "refresh-token-ignored");

            verify(valueOperations).set(eq("auth:blacklist:jti-access-999"), eq("REVOKED"), any(Duration.class));
            verify(redisTemplate).delete("auth:refresh:1");
        }

        @Test
        @DisplayName("logout - with expired access token: does not blacklist with negative TTL but deletes refresh token")
        void logout_withExpiredAccessToken_doesNotBlacklistNegativeTTL() {
            Instant expiresAt = Instant.now().minusSeconds(10);
            UserPrincipal principal = new UserPrincipal(1L, "0901234567", "Nguyen Van A", "jti-access-expired", expiresAt);

            authService.logout(principal, null);

            verify(valueOperations, never()).set(eq("auth:blacklist:jti-access-expired"), any(), any(Duration.class));
            verify(redisTemplate).delete("auth:refresh:1");
        }

        @Test
        @DisplayName("logout - null principal with valid refresh token: deletes refresh token by extracted userId")
        void logout_nullPrincipalWithValidRefreshToken_deletesRefreshToken() {
            String refreshToken = "valid.refresh.token";
            when(jwtTokenProvider.validateToken(refreshToken)).thenReturn(true);
            when(jwtTokenProvider.getUserIdFromToken(refreshToken)).thenReturn(2L);

            authService.logout(null, refreshToken);

            verify(redisTemplate).delete("auth:refresh:2");
        }
    }
}
