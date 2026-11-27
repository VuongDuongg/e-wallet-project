package com.meme.vwallet.service.user;

import com.meme.vwallet.dto.response.UserLookupResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.InvalidPhoneFormatException;
import com.meme.vwallet.exception.RateLimitExceededException;
import com.meme.vwallet.exception.UserNotFoundException;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.util.PhoneNumberUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;

/**
 * Service for rate-limited recipient phone lookup with system-user protection.
 */
@Service
@RequiredArgsConstructor
public class UserLookupService {

    private static final long SYSTEM_USER_ID = 1L;
    private static final long SYSTEM_WALLET_ID = 1L;
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:lookup:";
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${vwallet.lookup.rate-limit-per-minute:30}")
    private long maxPerMinute = 30;

    @Autowired
    @Lazy
    private UserLookupService self;

    /**
     * Validates and normalizes the Vietnamese phone number, enforces per-user Redis rate limiting,
     * and returns recipient metadata while hiding the system reserve account.
     */
    public UserLookupResponse lookupByPhone(Long currentUserId, String rawPhone) {
        String normalizedPhone;
        try {
            normalizedPhone = PhoneNumberUtils.normalize(rawPhone);
        } catch (IllegalArgumentException ex) {
            throw new InvalidPhoneFormatException("Số điện thoại không đúng định dạng Việt Nam");
        }

        enforceRateLimit(currentUserId);

        return (self != null ? self : this).loadRecipientResponse(currentUserId, normalizedPhone);
    }

    @Transactional(readOnly = true)
    public UserLookupResponse loadRecipientResponse(Long currentUserId, String normalizedPhone) {
        User targetUser = userRepository.findByPhoneNumber(normalizedPhone)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy người dùng với số điện thoại: " + normalizedPhone));

        if (Long.valueOf(SYSTEM_USER_ID).equals(targetUser.getId())) {
            throw new UserNotFoundException("Không tìm thấy người dùng với số điện thoại: " + normalizedPhone);
        }

        Wallet targetWallet = walletRepository.findByUserId(targetUser.getId())
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy ví của người dùng: " + normalizedPhone));
        if (Long.valueOf(SYSTEM_WALLET_ID).equals(targetWallet.getId())) {
            throw new UserNotFoundException("Không tìm thấy ví của người dùng: " + normalizedPhone);
        }

        boolean isSelf = Objects.equals(targetUser.getId(), currentUserId);
        boolean canReceive = !isSelf
                && "ACTIVE".equals(targetUser.getStatus())
                && targetWallet.getStatus() == WalletStatus.ACTIVE;

        return UserLookupResponse.builder()
                .phoneNumber(targetUser.getPhoneNumber())
                .fullName(targetUser.getFullName())
                .isSelf(isSelf)
                .canReceive(canReceive)
                .build();
    }

    private void enforceRateLimit(Long currentUserId) {
        String rateKey = RATE_LIMIT_KEY_PREFIX + currentUserId;
        Long count = redisTemplate.opsForValue().increment(rateKey);
        Long ttl = redisTemplate.getExpire(rateKey);
        if ((count != null && count == 1L) || ttl == null || ttl < 0) {
            redisTemplate.expire(rateKey, RATE_LIMIT_WINDOW);
        }
        if (count != null && count > maxPerMinute) {
            throw new RateLimitExceededException("Bạn đã tra cứu quá nhiều lần, vui lòng thử lại sau");
        }
    }
}
