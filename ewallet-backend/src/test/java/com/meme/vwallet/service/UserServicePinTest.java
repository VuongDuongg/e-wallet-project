package com.meme.vwallet.service;

import com.meme.vwallet.service.user.UserService;

import com.meme.vwallet.entity.User;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.PinAlreadySetException;
import com.meme.vwallet.exception.PinNotSetException;
import com.meme.vwallet.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServicePinTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .id(1L)
                .phoneNumber("0901234567")
                .fullName("Nguyen Van A")
                .passwordHash("hashedPassword")
                .pinHash("$2a$10$encodedPinHashValue")
                .status("ACTIVE")
                .build();
    }

    @Nested
    @DisplayName("verifyPin")
    class VerifyPinTests {

        @Test
        @DisplayName("should pass when raw PIN matches encoded pin_hash")
        void verifyPin_withCorrectPin_shouldPass() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
            when(passwordEncoder.matches("123456", sampleUser.getPinHash())).thenReturn(true);

            assertThatCode(() -> userService.verifyPin(1L, "123456"))
                    .doesNotThrowAnyException();

            verify(userRepository).findById(1L);
            verify(passwordEncoder).matches("123456", "$2a$10$encodedPinHashValue");
        }

        @Test
        @DisplayName("should throw PinNotSetException when user has null pin_hash")
        void verifyPin_whenPinHashIsNull_shouldThrowPinNotSetException() {
            sampleUser.setPinHash(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

            assertThatThrownBy(() -> userService.verifyPin(1L, "123456"))
                    .isInstanceOf(PinNotSetException.class)
                    .hasMessageContaining("chưa cài đặt mã PIN");

            verify(userRepository).findById(1L);
            verify(passwordEncoder, never()).matches(any(), any());
        }

        @Test
        @DisplayName("should throw PinNotSetException when user has blank pin_hash")
        void verifyPin_whenPinHashIsBlank_shouldThrowPinNotSetException() {
            sampleUser.setPinHash("   ");
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

            assertThatThrownBy(() -> userService.verifyPin(1L, "123456"))
                    .isInstanceOf(PinNotSetException.class);

            verify(userRepository).findById(1L);
            verify(passwordEncoder, never()).matches(any(), any());
        }

        @Test
        @DisplayName("should throw InvalidPinException when raw PIN does not match encoded pin_hash")
        void verifyPin_withIncorrectPin_shouldThrowInvalidPinException() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
            when(passwordEncoder.matches("999999", sampleUser.getPinHash())).thenReturn(false);

            assertThatThrownBy(() -> userService.verifyPin(1L, "999999"))
                    .isInstanceOf(InvalidPinException.class);

            verify(userRepository).findById(1L);
            verify(passwordEncoder).matches("999999", "$2a$10$encodedPinHashValue");
        }
    }

    @Nested
    @DisplayName("setInitialPin")
    class SetInitialPinTests {

        @Test
        @DisplayName("should throw PinAlreadySetException when user already has pin_hash")
        void setInitialPin_whenPinAlreadySet_shouldThrowPinAlreadySetException() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

            assertThatThrownBy(() -> userService.setInitialPin(1L, "123456"))
                    .isInstanceOf(PinAlreadySetException.class)
                    .hasMessageContaining("đã được thiết lập");

            verify(userRepository).findById(1L);
            verify(passwordEncoder, never()).encode(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should encode pin and save when user has null pin_hash")
        void setInitialPin_whenPinIsNull_shouldEncodeAndSave() {
            sampleUser.setPinHash(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
            when(passwordEncoder.encode("123456")).thenReturn("$2a$10$newPinHash");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.setInitialPin(1L, "123456");

            verify(userRepository).findById(1L);
            verify(passwordEncoder).encode("123456");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getPinHash()).isEqualTo("$2a$10$newPinHash");
        }

        @Test
        @DisplayName("should encode pin and save when user has blank pin_hash")
        void setInitialPin_whenPinIsBlank_shouldEncodeAndSave() {
            sampleUser.setPinHash("   ");
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
            when(passwordEncoder.encode("654321")).thenReturn("$2a$10$anotherPinHash");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.setInitialPin(1L, "654321");

            verify(userRepository).findById(1L);
            verify(passwordEncoder).encode("654321");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getPinHash()).isEqualTo("$2a$10$anotherPinHash");
        }
    }
}
