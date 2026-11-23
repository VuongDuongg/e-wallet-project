package com.meme.vwallet.dto;

import com.meme.vwallet.dto.request.LoginRequest;
import com.meme.vwallet.dto.request.PinSetupRequest;
import com.meme.vwallet.dto.request.RefreshTokenRequest;
import com.meme.vwallet.dto.request.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Nested
    @DisplayName("RegisterRequest Validation")
    class RegisterRequestValidation {

        @Test
        @DisplayName("valid RegisterRequest should have no violations")
        void validRequest_shouldHaveNoViolations() {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber("0912345678")
                    .fullName("Nguyen Van A")
                    .password("Password123")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0912345678", "+84912345678", "0323456789", "0812345678", "0712345678", "0512345678"})
        @DisplayName("valid VN phone numbers should pass")
        void validPhoneNumbers(String phone) {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber(phone)
                    .fullName("Nguyen Van A")
                    .password("Password123")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345", "0123456789", "091234567", "091234567890", "abcd", ""})
        @DisplayName("invalid phone numbers should fail")
        void invalidPhoneNumbers(String phone) {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber(phone)
                    .fullName("Nguyen Van A")
                    .password("Password123")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("short password should fail")
        void shortPassword_shouldFail() {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber("0912345678")
                    .fullName("Nguyen Van A")
                    .password("Pass123")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("8");
        }

        @Test
        @DisplayName("password without letter should fail")
        void passwordWithoutLetter_shouldFail() {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber("0912345678")
                    .fullName("Nguyen Van A")
                    .password("12345678")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("chữ cái");
        }

        @Test
        @DisplayName("password without digit should fail")
        void passwordWithoutDigit_shouldFail() {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber("0912345678")
                    .fullName("Nguyen Van A")
                    .password("PasswordOnly")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).contains("chữ số");
        }

        @Test
        @DisplayName("blank fullName should fail")
        void blankFullName_shouldFail() {
            RegisterRequest req = RegisterRequest.builder()
                    .phoneNumber("0912345678")
                    .fullName(" ")
                    .password("Password123")
                    .build();

            Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("LoginRequest Validation")
    class LoginRequestValidation {

        @Test
        @DisplayName("valid LoginRequest should pass")
        void validLogin_shouldPass() {
            LoginRequest req = LoginRequest.builder()
                    .phoneNumber("0901234567")
                    .password("anyPassword")
                    .build();

            Set<ConstraintViolation<LoginRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("blank fields should fail")
        void blankFields_shouldFail() {
            LoginRequest req = LoginRequest.builder()
                    .phoneNumber("")
                    .password("")
                    .build();

            Set<ConstraintViolation<LoginRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("RefreshTokenRequest Validation")
    class RefreshTokenRequestValidation {

        @Test
        @DisplayName("valid RefreshTokenRequest should pass")
        void validRefreshToken_shouldPass() {
            RefreshTokenRequest req = RefreshTokenRequest.builder()
                    .refreshToken("some.jwt.token")
                    .build();

            Set<ConstraintViolation<RefreshTokenRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("blank refreshToken should fail")
        void blankRefreshToken_shouldFail() {
            RefreshTokenRequest req = RefreshTokenRequest.builder()
                    .refreshToken("")
                    .build();

            Set<ConstraintViolation<RefreshTokenRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("PinSetupRequest Validation")
    class PinSetupRequestValidation {

        @Test
        @DisplayName("valid 6-digit PIN should pass")
        void validPin_shouldPass() {
            PinSetupRequest req = PinSetupRequest.builder()
                    .pin("123456")
                    .confirmPin("123456")
                    .build();

            Set<ConstraintViolation<PinSetupRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345", "1234567", "abcdef", "12a456", ""})
        @DisplayName("invalid PIN format should fail")
        void invalidPin_shouldFail(String pin) {
            PinSetupRequest req = PinSetupRequest.builder()
                    .pin(pin)
                    .confirmPin(pin)
                    .build();

            Set<ConstraintViolation<PinSetupRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }
}
