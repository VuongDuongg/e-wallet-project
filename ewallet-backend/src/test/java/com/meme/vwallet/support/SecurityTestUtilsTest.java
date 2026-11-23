package com.meme.vwallet.support;

import com.meme.vwallet.security.JwtTokenProvider;
import com.meme.vwallet.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityTestUtilsTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        // 256-bit secret for testing
        String secret = "dGhpcy1pcy1hLXZlcnktc2VjdXJlLXRlc3Qtc2VjcmV0LWtleS1mb3Itand0LTEyMzQ1Njc4OTA=";
        jwtTokenProvider = new JwtTokenProvider(secret, 900L, 604800L);
    }

    @AfterEach
    void tearDown() {
        SecurityTestUtils.clearSecurityContext();
    }

    @Test
    @DisplayName("setAuthenticatedUser should populate SecurityContextHolder with valid UserPrincipal")
    void testSetAuthenticatedUser() {
        UserPrincipal principal = SecurityTestUtils.setAuthenticatedUser(42L, "0901234567", "John Doe");

        assertThat(principal.getUserId()).isEqualTo(42L);
        assertThat(principal.getPhoneNumber()).isEqualTo("0901234567");
        assertThat(principal.getFullName()).isEqualTo("John Doe");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(principal);
        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("setAuthenticatedUser default overload should use default phone and name")
    void testSetAuthenticatedUserDefaultOverload() {
        UserPrincipal principal = SecurityTestUtils.setAuthenticatedUser(99L);

        assertThat(principal.getUserId()).isEqualTo(99L);
        assertThat(principal.getPhoneNumber()).isEqualTo(SecurityTestUtils.DEFAULT_PHONE);
        assertThat(principal.getFullName()).isEqualTo(SecurityTestUtils.DEFAULT_NAME);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(principal);
    }

    @Test
    @DisplayName("clearSecurityContext should remove authentication from SecurityContextHolder")
    void testClearSecurityContext() {
        SecurityTestUtils.setAuthenticatedUser(1L);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        SecurityTestUtils.clearSecurityContext();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("createBearerToken should return token starting with 'Bearer ' and valid claims")
    void testCreateBearerToken() {
        String bearerToken = SecurityTestUtils.createBearerToken(jwtTokenProvider, 55L, "0912345678", "Alice");

        assertThat(bearerToken).startsWith("Bearer ");
        String rawToken = bearerToken.substring(7);

        assertThat(jwtTokenProvider.validateToken(rawToken)).isTrue();
        assertThat(jwtTokenProvider.getUserIdFromToken(rawToken)).isEqualTo(55L);
        assertThat(jwtTokenProvider.getTokenType(rawToken)).isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS);
    }

    @Test
    @DisplayName("createBearerToken default overload should work as expected")
    void testCreateBearerTokenDefault() {
        String bearerToken = SecurityTestUtils.createBearerToken(jwtTokenProvider, 77L);

        assertThat(bearerToken).startsWith("Bearer ");
        String rawToken = bearerToken.substring(7);

        assertThat(jwtTokenProvider.validateToken(rawToken)).isTrue();
        assertThat(jwtTokenProvider.getUserIdFromToken(rawToken)).isEqualTo(77L);
    }

    @Test
    @DisplayName("generateAccessToken should return raw token without 'Bearer ' prefix")
    void testGenerateAccessToken() {
        String token = SecurityTestUtils.generateAccessToken(jwtTokenProvider, 88L);

        assertThat(token).doesNotStartWith("Bearer ");
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(88L);
    }
}
