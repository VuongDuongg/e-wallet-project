package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.UserLookupResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.InvalidPhoneFormatException;
import com.meme.vwallet.exception.RateLimitExceededException;
import com.meme.vwallet.exception.UserNotFoundException;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLookupServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private UserLookupService userLookupService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("lookup valid +84 phone normalizes to 0... and returns active recipient with isSelf=false, canReceive=true")
    void lookup_validPhone_returnsActiveRecipient() {
        Long currentUserId = 2L;
        String rateKey = "ratelimit:lookup:2";

        User targetUser = User.builder()
                .id(3L)
                .phoneNumber("0987654321")
                .fullName("Tran Thi B")
                .status("ACTIVE")
                .build();
        Wallet targetWallet = Wallet.builder()
                .id(3L)
                .user(targetUser)
                .status(WalletStatus.ACTIVE)
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(1L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(-1L);
        when(userRepository.findByPhoneNumber("0987654321")).thenReturn(Optional.of(targetUser));
        when(walletRepository.findByUserId(3L)).thenReturn(Optional.of(targetWallet));

        UserLookupResponse response = userLookupService.lookupByPhone(currentUserId, "+84987654321");

        assertThat(response.getPhoneNumber()).isEqualTo("0987654321");
        assertThat(response.getFullName()).isEqualTo("Tran Thi B");
        assertThat(response.isSelf()).isFalse();
        assertThat(response.isCanReceive()).isTrue();
        verify(redisTemplate).expire(rateKey, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("lookup own phone returns isSelf=true and canReceive=false")
    void lookup_selfPhone_returnsIsSelfTrueAndCanReceiveFalse() {
        Long currentUserId = 2L;
        String rateKey = "ratelimit:lookup:2";

        User selfUser = User.builder()
                .id(2L)
                .phoneNumber("0901234567")
                .fullName("Nguyen Van A")
                .status("ACTIVE")
                .build();
        Wallet selfWallet = Wallet.builder()
                .id(2L)
                .user(selfUser)
                .status(WalletStatus.ACTIVE)
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(2L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(45L);
        when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.of(selfUser));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(selfWallet));

        UserLookupResponse response = userLookupService.lookupByPhone(currentUserId, "0901234567");

        assertThat(response.getPhoneNumber()).isEqualTo("0901234567");
        assertThat(response.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(response.isSelf()).isTrue();
        assertThat(response.isCanReceive()).isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("lookup locked target user returns canReceive=false")
    void lookup_lockedTargetUser_returnsCanReceiveFalse() {
        Long currentUserId = 2L;
        String rateKey = "ratelimit:lookup:2";

        User lockedUser = User.builder()
                .id(3L)
                .phoneNumber("0987654321")
                .fullName("Tran Thi B")
                .status("LOCKED")
                .build();
        Wallet activeWallet = Wallet.builder()
                .id(3L)
                .user(lockedUser)
                .status(WalletStatus.ACTIVE)
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(2L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(50L);
        when(userRepository.findByPhoneNumber("0987654321")).thenReturn(Optional.of(lockedUser));
        when(walletRepository.findByUserId(3L)).thenReturn(Optional.of(activeWallet));

        UserLookupResponse response = userLookupService.lookupByPhone(currentUserId, "0987654321");

        assertThat(response.isSelf()).isFalse();
        assertThat(response.isCanReceive()).isFalse();
    }

    @Test
    @DisplayName("lookup locked wallet returns canReceive=false")
    void lookup_lockedWallet_returnsCanReceiveFalse() {
        Long currentUserId = 2L;
        String rateKey = "ratelimit:lookup:2";

        User activeUser = User.builder()
                .id(3L)
                .phoneNumber("0987654321")
                .fullName("Tran Thi B")
                .status("ACTIVE")
                .build();
        Wallet lockedWallet = Wallet.builder()
                .id(3L)
                .user(activeUser)
                .status(WalletStatus.LOCKED)
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(2L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(50L);
        when(userRepository.findByPhoneNumber("0987654321")).thenReturn(Optional.of(activeUser));
        when(walletRepository.findByUserId(3L)).thenReturn(Optional.of(lockedWallet));

        UserLookupResponse response = userLookupService.lookupByPhone(currentUserId, "0987654321");

        assertThat(response.isSelf()).isFalse();
        assertThat(response.isCanReceive()).isFalse();
    }

    @Test
    @DisplayName("lookup system phone 0000000000 fails regex early with InvalidPhoneFormatException (400)")
    void lookup_systemPhone0000000000_throwsInvalidPhoneFormat400() {
        assertThatThrownBy(() -> userLookupService.lookupByPhone(2L, "0000000000"))
                .isInstanceOf(InvalidPhoneFormatException.class);

        verify(valueOperations, never()).increment(anyString());
        verify(userRepository, never()).findByPhoneNumber(anyString());
    }

    @Test
    @DisplayName("lookup DB record with SYSTEM_USER id=1 throws UserNotFoundException (404)")
    void lookup_dbSystemUserId1_throwsUserNotFound404() {
        String rateKey = "ratelimit:lookup:2";
        User systemUser = User.builder()
                .id(1L)
                .phoneNumber("0909999999")
                .fullName("SYSTEM_USER")
                .status("ACTIVE")
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(1L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(60L);
        when(userRepository.findByPhoneNumber("0909999999")).thenReturn(Optional.of(systemUser));

        assertThatThrownBy(() -> userLookupService.lookupByPhone(2L, "0909999999"))
                .isInstanceOf(UserNotFoundException.class);

        verify(walletRepository, never()).findByUserId(1L);
    }

    @Test
    @DisplayName("lookup exceeding 30 requests per minute throws RateLimitExceededException (429)")
    void lookup_rateLimitExceeded_throws429() {
        String rateKey = "ratelimit:lookup:2";
        when(valueOperations.increment(rateKey)).thenReturn(31L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(20L);

        assertThatThrownBy(() -> userLookupService.lookupByPhone(2L, "0987654321"))
                .isInstanceOf(RateLimitExceededException.class);

        verify(userRepository, never()).findByPhoneNumber(anyString());
    }

    @Test
    @DisplayName("lookup orphan counter with count > 1 and no TTL (-1) re-applies 60s TTL")
    void lookup_orphanCounterWithoutTtl_setsTtl60s() {
        String rateKey = "ratelimit:lookup:2";
        User targetUser = User.builder()
                .id(3L)
                .phoneNumber("0987654321")
                .fullName("Tran Thi B")
                .status("ACTIVE")
                .build();
        Wallet targetWallet = Wallet.builder()
                .id(3L)
                .user(targetUser)
                .status(WalletStatus.ACTIVE)
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(5L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(-1L);
        when(userRepository.findByPhoneNumber("0987654321")).thenReturn(Optional.of(targetUser));
        when(walletRepository.findByUserId(3L)).thenReturn(Optional.of(targetWallet));

        UserLookupResponse response = userLookupService.lookupByPhone(2L, "0987654321");

        assertThat(response.isCanReceive()).isTrue();
        verify(redisTemplate).expire(rateKey, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("lookup target linked to SYSTEM_WALLET id=1 throws UserNotFoundException (404)")
    void lookup_dbSystemWalletId1_throwsUserNotFound404() {
        String rateKey = "ratelimit:lookup:2";
        User edgeUser = User.builder()
                .id(99L)
                .phoneNumber("0908888888")
                .fullName("Edge System Wallet User")
                .status("ACTIVE")
                .build();
        Wallet systemWallet = Wallet.builder()
                .id(1L)
                .user(edgeUser)
                .status(WalletStatus.ACTIVE)
                .build();

        when(valueOperations.increment(rateKey)).thenReturn(1L);
        when(redisTemplate.getExpire(rateKey)).thenReturn(60L);
        when(userRepository.findByPhoneNumber("0908888888")).thenReturn(Optional.of(edgeUser));
        when(walletRepository.findByUserId(99L)).thenReturn(Optional.of(systemWallet));

        assertThatThrownBy(() -> userLookupService.lookupByPhone(2L, "0908888888"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
