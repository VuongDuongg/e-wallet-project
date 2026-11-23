package com.meme.vwallet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String SECRET = "dGhpc19pc19hX3Zlcnlfc2VjdXJlXzI1Nl9iaXRfc2VjcmV0X2tleV9mb3JfdndhbGxldF9kZXYh";
    private static final long ACCESS_TOKEN_EXPIRATION_SECONDS = 900;
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 604800;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                SECRET,
                ACCESS_TOKEN_EXPIRATION_SECONDS,
                REFRESH_TOKEN_EXPIRATION_SECONDS
        );
    }

    @Test
    @DisplayName("Generate access token contains sub, phone, name, type='ACCESS', jti, and valid expiration")
    void generateAccessToken_containsRequiredClaims() {
        Long userId = 100L;
        String phone = "0912345678";
        String name = "Nguyen Van A";

        String token = jwtTokenProvider.generateAccessToken(userId, phone, name);
        assertNotNull(token);

        Claims claims = jwtTokenProvider.parseClaims(token);
        assertEquals(String.valueOf(userId), claims.getSubject());
        assertEquals(phone, claims.get("phone", String.class));
        assertEquals(name, claims.get("name", String.class));
        assertEquals("ACCESS", claims.get("type", String.class));
        assertNotNull(claims.getId()); // jti
        assertDoesNotThrow(() -> UUID.fromString(claims.getId()));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());

        long validityDuration = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertEquals(ACCESS_TOKEN_EXPIRATION_SECONDS, validityDuration);
    }

    @Test
    @DisplayName("Generate refresh token contains sub, type='REFRESH', jti=tokenId, and 7d expiration")
    void generateRefreshToken_containsRequiredClaims() {
        Long userId = 200L;
        String tokenId = UUID.randomUUID().toString();

        String token = jwtTokenProvider.generateRefreshToken(userId, tokenId);
        assertNotNull(token);

        Claims claims = jwtTokenProvider.parseClaims(token);
        assertEquals(String.valueOf(userId), claims.getSubject());
        assertEquals("REFRESH", claims.get("type", String.class));
        assertEquals(tokenId, claims.getId());
        assertNull(claims.get("phone"));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());

        long validityDuration = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertEquals(REFRESH_TOKEN_EXPIRATION_SECONDS, validityDuration);
    }

    @Test
    @DisplayName("validateToken returns true for newly generated access token")
    void validateToken_validToken_returnsTrue() {
        String token = jwtTokenProvider.generateAccessToken(1L, "0912345678", "Test User");
        assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    @DisplayName("validateToken returns false for expired token")
    void validateToken_expiredToken_returnsFalse() {
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(SECRET, -10, -10);
        String expiredToken = shortLivedProvider.generateAccessToken(1L, "0912345678", "Test User");

        assertFalse(jwtTokenProvider.validateToken(expiredToken));
    }

    @Test
    @DisplayName("validateToken returns false for tampered signature")
    void validateToken_tamperedToken_returnsFalse() {
        String token = jwtTokenProvider.generateAccessToken(1L, "0912345678", "Test User");
        String tamperedToken = token + "xyz";

        assertFalse(jwtTokenProvider.validateToken(tamperedToken));
    }

    @Test
    @DisplayName("validateToken returns false for malformed token or null")
    void validateToken_malformedOrNull_returnsFalse() {
        assertFalse(jwtTokenProvider.validateToken("not.a.valid.jwt.token"));
        assertFalse(jwtTokenProvider.validateToken(""));
        assertFalse(jwtTokenProvider.validateToken(null));
    }

    @Test
    @DisplayName("getTokenType correctly extracts 'ACCESS' and 'REFRESH'")
    void getTokenType_returnsCorrectType() {
        String accessToken = jwtTokenProvider.generateAccessToken(1L, "0912345678", "User");
        String refreshToken = jwtTokenProvider.generateRefreshToken(1L, UUID.randomUUID().toString());

        assertEquals("ACCESS", jwtTokenProvider.getTokenType(accessToken));
        assertEquals("REFRESH", jwtTokenProvider.getTokenType(refreshToken));
    }

    @Test
    @DisplayName("parseClaims throws ExpiredJwtException when parsing expired token")
    void parseClaims_expiredToken_throwsExpiredJwtException() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -5, -5);
        String expiredToken = expiredProvider.generateAccessToken(1L, "0912345678", "User");

        assertThrows(ExpiredJwtException.class, () -> jwtTokenProvider.parseClaims(expiredToken));
    }

    @Test
    @DisplayName("getUserPrincipal extracts UserPrincipal correctly from access token")
    void getUserPrincipal_validAccessToken_extractsUserPrincipal() {
        Long userId = 42L;
        String phone = "0987654321";
        String name = "Nguyen Van B";
        String token = jwtTokenProvider.generateAccessToken(userId, phone, name);

        UserPrincipal principal = jwtTokenProvider.getUserPrincipal(token);
        assertNotNull(principal);
        assertEquals(userId, principal.getUserId());
        assertEquals(phone, principal.getPhoneNumber());
        assertEquals(name, principal.getFullName());
        assertNotNull(principal.getJti());
        assertNotNull(principal.getExpiresAt());
        assertTrue(principal.getExpiresAt().isAfter(new Date().toInstant().minusSeconds(5)));
    }
}
