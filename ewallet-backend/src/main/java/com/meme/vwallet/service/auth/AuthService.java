package com.meme.vwallet.service.auth;

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
import com.meme.vwallet.exception.UserNotFoundException;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.security.JwtTokenProvider;
import com.meme.vwallet.security.UserPrincipal;
import com.meme.vwallet.util.PhoneNumberUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final long refreshGracePeriodSeconds;

    public AuthService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            @Value("${vwallet.jwt.refresh-grace-period-seconds:30}") long refreshGracePeriodSeconds
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.refreshGracePeriodSeconds = refreshGracePeriodSeconds;
    }

    /**
     * Registers a new user and creates an initial zero-balance active wallet in a single transaction.
     * Generates an initial token pair and registers the session in Redis.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedPhone = PhoneNumberUtils.normalize(request.getPhoneNumber());

        if (userRepository.existsByPhoneNumber(normalizedPhone)) {
            throw new UserAlreadyExistsException("Số điện thoại đã được đăng ký");
        }

        User user = User.builder()
                .phoneNumber(normalizedPhone)
                .fullName(request.getFullName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status("ACTIVE")
                .build();
        user = userRepository.save(user);

        Wallet wallet = Wallet.builder()
                .user(user)
                .balance(0L)
                .currency("VND")
                .version(0L)
                .status(WalletStatus.ACTIVE)
                .build();
        walletRepository.save(wallet);

        String tokenId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getPhoneNumber(), user.getFullName());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), tokenId);

        redisTemplate.opsForValue().set(
                "auth:refresh:" + user.getId(),
                tokenId,
                Duration.ofSeconds(jwtTokenProvider.getRefreshTokenExpirationSeconds())
        );

        return AuthResponse.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .fullName(user.getFullName())
                .hasPin(user.hasPin())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build();
    }

    /**
     * Authenticates a user by phone and password.
     * Overwrites previous Redis refresh token session with a new token pair.
     */
    public AuthResponse login(LoginRequest request) {
        String normalizedPhone = PhoneNumberUtils.normalize(request.getPhoneNumber());

        User user = userRepository.findByPhoneNumber(normalizedPhone)
                .orElseThrow(() -> new BadCredentialsException("Số điện thoại hoặc mật khẩu không chính xác"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Số điện thoại hoặc mật khẩu không chính xác");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new UserLockedException("Tài khoản của bạn đã bị khóa");
        }

        String tokenId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getPhoneNumber(), user.getFullName());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), tokenId);

        redisTemplate.opsForValue().set(
                "auth:refresh:" + user.getId(),
                tokenId,
                Duration.ofSeconds(jwtTokenProvider.getRefreshTokenExpirationSeconds())
        );

        return AuthResponse.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .fullName(user.getFullName())
                .hasPin(user.hasPin())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build();
    }

    /**
     * Refreshes JWT tokens using Single-Device Refresh Token Rotation (RTR) with Redis concurrency lock
     * and a configurable grace period cache.
     */
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)) {
            throw new InvalidTokenTypeException("Refresh token không hợp lệ hoặc đã hết hạn");
        }

        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!JwtTokenProvider.TOKEN_TYPE_REFRESH.equals(tokenType)) {
            throw new InvalidTokenTypeException("Token type không hợp lệ, yêu cầu Refresh Token");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        String tokenId = jwtTokenProvider.getJtiFromToken(refreshToken);

        // Fast-check auth:rotated:{tokenId} in Redis: if present, return cached AuthResponse
        String rotatedKey = "auth:rotated:" + tokenId;
        Object cachedRotated = redisTemplate.opsForValue().get(rotatedKey);
        if (cachedRotated != null) {
            AuthResponse cachedResponse = deserializeAuthResponse(cachedRotated);
            if (cachedResponse != null) {
                return cachedResponse;
            }
        }

        String lockKey = "auth:refresh_lock:" + userId;
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(5));
        boolean lockAcquired = Boolean.TRUE.equals(acquired);

        if (!lockAcquired) {
            // Retry up to 3 times (100ms interval)
            for (int i = 0; i < 3; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ConflictException("Yêu cầu đang được xử lý, vui lòng thử lại");
                }
                cachedRotated = redisTemplate.opsForValue().get(rotatedKey);
                if (cachedRotated != null) {
                    AuthResponse cachedResponse = deserializeAuthResponse(cachedRotated);
                    if (cachedResponse != null) {
                        return cachedResponse;
                    }
                }
            }
            throw new ConflictException("Yêu cầu đang được xử lý, vui lòng thử lại");
        }

        try {
            // Double-check auth:rotated:{tokenId}
            cachedRotated = redisTemplate.opsForValue().get(rotatedKey);
            if (cachedRotated != null) {
                AuthResponse cachedResponse = deserializeAuthResponse(cachedRotated);
                if (cachedResponse != null) {
                    return cachedResponse;
                }
            }

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException("Không tìm thấy người dùng"));

            if (!"ACTIVE".equals(user.getStatus())) {
                throw new UserLockedException("Tài khoản của bạn đã bị khóa");
            }

            String activeRefreshKey = "auth:refresh:" + userId;
            Object activeTokenId = redisTemplate.opsForValue().get(activeRefreshKey);
            if (activeTokenId == null || !tokenId.equals(String.valueOf(activeTokenId))) {
                // Containment: revoke active refresh token
                redisTemplate.delete(activeRefreshKey);
                throw new TokenReuseException("Phát hiện token đã qua sử dụng hoặc phiên đăng nhập không hợp lệ");
            }

            String newTokenId = UUID.randomUUID().toString();
            String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getPhoneNumber(), user.getFullName());
            String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), newTokenId);

            AuthResponse authResponse = AuthResponse.builder()
                    .userId(user.getId())
                    .phoneNumber(user.getPhoneNumber())
                    .fullName(user.getFullName())
                    .hasPin(user.hasPin())
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .tokenType("Bearer")
                    .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                    .build();

            // Update active session
            redisTemplate.opsForValue().set(
                    activeRefreshKey,
                    newTokenId,
                    Duration.ofSeconds(jwtTokenProvider.getRefreshTokenExpirationSeconds())
            );

            // Cache old token with grace period TTL
            try {
                String serialized = objectMapper.writeValueAsString(authResponse);
                redisTemplate.opsForValue().set(
                        rotatedKey,
                        serialized,
                        Duration.ofSeconds(refreshGracePeriodSeconds)
                );
            } catch (Exception e) {
                log.error("Failed to serialize rotated AuthResponse for token {}", tokenId, e);
            }

            return authResponse;
        } finally {
            Object currentLock = redisTemplate.opsForValue().get(lockKey);
            if (lockToken.equals(currentLock)) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * Terminates session: blacklists the current access token jti in Redis with remaining TTL,
     * and evicts the active refresh token.
     */
    public void logout(UserPrincipal principal, String refreshToken) {
        Long userId = null;
        if (principal != null) {
            userId = principal.getUserId();
            String jti = principal.getJti();
            Instant expiresAt = principal.getExpiresAt();
            if (jti != null && expiresAt != null) {
                long remainingSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
                if (remainingSeconds > 0) {
                    redisTemplate.opsForValue().set("auth:blacklist:" + jti, "REVOKED", Duration.ofSeconds(remainingSeconds));
                }
            }
        }
        if (userId == null && refreshToken != null && jwtTokenProvider.validateToken(refreshToken)) {
            try {
                userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
            } catch (Exception ignored) {
            }
        }
        if (userId != null) {
            redisTemplate.delete("auth:refresh:" + userId);
        }
    }

    private AuthResponse deserializeAuthResponse(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof AuthResponse authResponse) {
                return authResponse;
            }
            if (value instanceof String jsonStr) {
                return objectMapper.readValue(jsonStr, AuthResponse.class);
            }
            return objectMapper.convertValue(value, AuthResponse.class);
        } catch (Exception e) {
            log.error("Failed to deserialize cached AuthResponse", e);
            return null;
        }
    }
}
