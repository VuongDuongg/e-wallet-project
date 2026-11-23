package com.meme.vwallet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    public static final String CLAIM_PHONE = "phone";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_TYPE = "type";
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";
    public static final String TOKEN_TYPE_REFRESH = "REFRESH";

    private final String secret;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;
    private final SecretKey key;

    public JwtTokenProvider(
            @Value("${vwallet.jwt.secret}") String secret,
            @Value("${vwallet.jwt.access-token-expiration-seconds:900}") long accessTokenExpirationSeconds,
            @Value("${vwallet.jwt.refresh-token-expiration-seconds:604800}") long refreshTokenExpirationSeconds
    ) {
        this.secret = secret;
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.key = initKey(secret);
    }

    private SecretKey initKey(String secretKeyString) {
        if (secretKeyString == null || secretKeyString.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be null or blank");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretKeyString);
            if (keyBytes.length < 32) {
                keyBytes = secretKeyString.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            keyBytes = secretKeyString.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String phone, String name) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpirationSeconds);
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_PHONE, phone)
                .claim(CLAIM_NAME, name)
                .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Long userId, String tokenId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(refreshTokenExpirationSeconds);
        String jti = tokenId != null ? tokenId : UUID.randomUUID().toString();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getTokenType(String token) {
        Claims claims = parseClaims(token);
        return claims.get(CLAIM_TYPE, String.class);
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }

    public String getJtiFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.getId();
    }

    public UserPrincipal getUserPrincipal(String token) {
        Claims claims = parseClaims(token);
        return getUserPrincipal(claims);
    }

    public UserPrincipal getUserPrincipal(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String phone = claims.get(CLAIM_PHONE, String.class);
        String name = claims.get(CLAIM_NAME, String.class);
        String jti = claims.getId();
        Instant expiresAt = claims.getExpiration() != null ? claims.getExpiration().toInstant() : null;
        return new UserPrincipal(userId, phone, name, jti, expiresAt);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationSeconds;
    }
}
