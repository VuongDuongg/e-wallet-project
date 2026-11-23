package com.meme.vwallet.security;

import com.meme.vwallet.dto.response.ErrorResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final List<String> EXCLUDE_PATTERNS = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/payments/webhook",
            "/api/v1/payments/vnpay-return",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/actuator/**"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   RedisTemplate<String, Object> redisTemplate,
                                   ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   RedisTemplate<String, Object> redisTemplate) {
        this(jwtTokenProvider, redisTemplate, new ObjectMapper());
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        for (String pattern : EXCLUDE_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtTokenProvider.parseClaims(token);
            String tokenType = claims.get(JwtTokenProvider.CLAIM_TYPE, String.class);
            if (!JwtTokenProvider.TOKEN_TYPE_ACCESS.equals(tokenType)) {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_TYPE", "Token type không hợp lệ, yêu cầu Access Token");
                return;
            }

            UserPrincipal principal = jwtTokenProvider.getUserPrincipal(claims);
            Boolean isBlacklisted = redisTemplate.hasKey("auth:blacklist:" + principal.getJti());
            if (Boolean.TRUE.equals(isBlacklisted)) {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token đã bị thu hồi hoặc đã đăng xuất");
                return;
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Token đã hết hạn");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token không hợp lệ");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .code(code)
                .errorCode(code)
                .message(message)
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
