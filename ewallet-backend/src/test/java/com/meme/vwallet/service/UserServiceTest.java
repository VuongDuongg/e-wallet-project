package com.meme.vwallet.service;

import com.meme.vwallet.entity.User;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.UserNotFoundException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

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
        @DisplayName("should throw InvalidPinException when raw PIN does not match encoded pin_hash")
        void verifyPin_withIncorrectPin_shouldThrowInvalidPinException() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
            when(passwordEncoder.matches("999999", sampleUser.getPinHash())).thenReturn(false);

            assertThatThrownBy(() -> userService.verifyPin(1L, "999999"))
                    .isInstanceOf(InvalidPinException.class)
                    .hasMessageContaining("Invalid PIN");

            verify(userRepository).findById(1L);
            verify(passwordEncoder).matches("999999", "$2a$10$encodedPinHashValue");
        }

        @Test
        @DisplayName("should throw InvalidPinException when user has null pin_hash")
        void verifyPin_whenPinHashIsNull_shouldThrowInvalidPinException() {
            sampleUser.setPinHash(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

            assertThatThrownBy(() -> userService.verifyPin(1L, "123456"))
                    .isInstanceOf(InvalidPinException.class)
                    .hasMessageContaining("Invalid PIN");

            verify(userRepository).findById(1L);
        }

        @Test
        @DisplayName("should throw UserNotFoundException when user does not exist")
        void verifyPin_whenUserNotFound_shouldThrowUserNotFoundException() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.verifyPin(99L, "123456"))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining("99");

            verify(userRepository).findById(99L);
        }
    }

    @Nested
    @DisplayName("setPin")
    class SetPinTests {

        @Test
        @DisplayName("should hash new PIN and save updated user")
        void setPin_shouldEncodePinAndSave() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
            when(passwordEncoder.encode("654321")).thenReturn("$2a$10$newEncodedHash");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.setPin(1L, "654321");

            verify(userRepository).findById(1L);
            verify(passwordEncoder).encode("654321");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getPinHash()).isEqualTo("$2a$10$newEncodedHash");
        }

        @Test
        @DisplayName("should throw UserNotFoundException when user to set PIN for is not found")
        void setPin_whenUserNotFound_shouldThrowUserNotFoundException() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.setPin(99L, "654321"))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining("99");

            verify(userRepository).findById(99L);
        }
    }

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("should return User when found by id")
        void findById_whenFound_shouldReturnUser() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

            User result = userService.findById(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getPhoneNumber()).isEqualTo("0901234567");
            verify(userRepository).findById(1L);
        }

        @Test
        @DisplayName("should throw UserNotFoundException when not found by id")
        void findById_whenNotFound_shouldThrowUserNotFoundException() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.findById(99L))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining("99");

            verify(userRepository).findById(99L);
        }
    }

    @Nested
    @DisplayName("findByPhoneNumber")
    class FindByPhoneNumberTests {

        @Test
        @DisplayName("should return User when found by phone number")
        void findByPhoneNumber_whenFound_shouldReturnUser() {
            when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.of(sampleUser));

            User result = userService.findByPhoneNumber("0901234567");

            assertThat(result).isNotNull();
            assertThat(result.getPhoneNumber()).isEqualTo("0901234567");
            verify(userRepository).findByPhoneNumber("0901234567");
        }

        @Test
        @DisplayName("should throw UserNotFoundException when not found by phone number")
        void findByPhoneNumber_whenNotFound_shouldThrowUserNotFoundException() {
            when(userRepository.findByPhoneNumber("0999999999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.findByPhoneNumber("0999999999"))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining("0999999999");

            verify(userRepository).findByPhoneNumber("0999999999");
        }
    }
}
