package com.meme.vwallet.support;

import com.meme.vwallet.security.JwtTokenProvider;
import com.meme.vwallet.security.UserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.UUID;

public final class SecurityTestUtils {

    public static final String DEFAULT_PHONE = "0901234567";
    public static final String DEFAULT_NAME = "Test User";

    private SecurityTestUtils() {
    }

    /**
     * Sets the Spring SecurityContext with an authenticated UserPrincipal.
     */
    public static UserPrincipal setAuthenticatedUser(Long userId, String phone, String name) {
        UserPrincipal principal = new UserPrincipal(
                userId,
                phone != null ? phone : DEFAULT_PHONE,
                name != null ? name : DEFAULT_NAME,
                UUID.randomUUID().toString(),
                Instant.now().plusSeconds(3600)
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
        return principal;
    }

    /**
     * Convenience overload for setting the SecurityContext with default phone and name.
     */
    public static UserPrincipal setAuthenticatedUser(Long userId) {
        return setAuthenticatedUser(userId, DEFAULT_PHONE, DEFAULT_NAME);
    }

    /**
     * Clears the Spring SecurityContext.
     */
    public static void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Generates a valid Bearer token string (prefixed with "Bearer ") for testing.
     */
    public static String createBearerToken(JwtTokenProvider tokenProvider, Long userId, String phone, String name) {
        return "Bearer " + tokenProvider.generateAccessToken(userId, phone, name);
    }

    /**
     * Convenience overload for generating a Bearer token with default phone and name.
     */
    public static String createBearerToken(JwtTokenProvider tokenProvider, Long userId) {
        return createBearerToken(tokenProvider, userId, DEFAULT_PHONE, DEFAULT_NAME);
    }

    /**
     * Generates a raw JWT access token string (without "Bearer " prefix).
     */
    public static String generateAccessToken(JwtTokenProvider tokenProvider, Long userId, String phone, String name) {
        return tokenProvider.generateAccessToken(userId, phone, name);
    }

    /**
     * Convenience overload for generating a raw JWT access token with default phone and name.
     */
    public static String generateAccessToken(JwtTokenProvider tokenProvider, Long userId) {
        return generateAccessToken(tokenProvider, userId, DEFAULT_PHONE, DEFAULT_NAME);
    }
}
