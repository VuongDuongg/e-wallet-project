package com.meme.vwallet.security;

import com.meme.vwallet.config.SecurityBeansConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private ObjectMapper objectMapper;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/payments/webhook",
            "/api/v1/payments/vnpay-return",
            "/swagger-ui/index.html",
            "/swagger-ui/swagger-initializer.js",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/info"
    })
    @DisplayName("shouldNotFilter should return true for public whitelist endpoints")
    void shouldNotFilter_publicEndpoints_shouldReturnTrue(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter should return true when requestURI has contextPath and servletPath is empty")
    void shouldNotFilter_withContextPathInRequestUri_shouldReturnTrue() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/v1/auth/login");
        request.setContextPath("/app");
        request.setRequestURI("/app/api/v1/auth/login");
        request.setServletPath("");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/transfers",
            "/api/v1/wallets/me",
            "/api/v1/wallets/1/pin",
            "/api/v1/users/me",
            "/api/v1/payments/topup"
    })
    @DisplayName("shouldNotFilter should return false for protected endpoints")
    void shouldNotFilter_protectedEndpoints_shouldReturnFalse(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    @DisplayName("doFilterInternal should proceed down filter chain when Authorization header is missing")
    void doFilterInternal_whenAuthorizationHeaderMissing_shouldProceedWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("doFilterInternal should proceed down filter chain when Authorization header does not start with Bearer")
    void doFilterInternal_whenAuthorizationHeaderNotBearer_shouldProceedWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("doFilterInternal should return 401 TOKEN_EXPIRED when token is expired")
    void doFilterInternal_whenTokenExpired_shouldReturn401TokenExpired() throws Exception {
        String token = "expired.jwt.token";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtTokenProvider.parseClaims(token)).thenThrow(new ExpiredJwtException(null, null, "Token has expired"));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull(); // Chain was NOT invoked
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
        assertThat(jsonNode.get("status").asInt()).isEqualTo(401);
        assertThat(jsonNode.get("code").asText()).isEqualTo("TOKEN_EXPIRED");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Token đã hết hạn");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal should return 401 INVALID_TOKEN when token is malformed or invalid")
    void doFilterInternal_whenTokenInvalid_shouldReturn401InvalidToken() throws Exception {
        String token = "malformed.jwt.token";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtTokenProvider.parseClaims(token)).thenThrow(new MalformedJwtException("Malformed token"));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
        assertThat(jsonNode.get("status").asInt()).isEqualTo(401);
        assertThat(jsonNode.get("code").asText()).isEqualTo("INVALID_TOKEN");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Token không hợp lệ");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal should return 401 INVALID_TOKEN when token argument is empty or illegal")
    void doFilterInternal_whenTokenEmpty_shouldReturn401InvalidToken() throws Exception {
        String token = "";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtTokenProvider.parseClaims(token)).thenThrow(new IllegalArgumentException("JWT string cannot be empty"));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
        assertThat(jsonNode.get("status").asInt()).isEqualTo(401);
        assertThat(jsonNode.get("code").asText()).isEqualTo("INVALID_TOKEN");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Token không hợp lệ");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal should return 401 INVALID_TOKEN_TYPE when token is not an ACCESS token")
    void doFilterInternal_whenTokenTypeNotAccess_shouldReturn401InvalidTokenType() throws Exception {
        String token = "valid.refresh.token";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        Claims claims = mock(Claims.class);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
        when(jwtTokenProvider.parseClaims(token)).thenReturn(claims);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
        assertThat(jsonNode.get("status").asInt()).isEqualTo(401);
        assertThat(jsonNode.get("code").asText()).isEqualTo("INVALID_TOKEN_TYPE");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Token type không hợp lệ, yêu cầu Access Token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal should return 401 TOKEN_REVOKED when token JTI is in Redis blacklist")
    void doFilterInternal_whenTokenIsBlacklisted_shouldReturn401TokenRevoked() throws Exception {
        String token = "blacklisted.access.token";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        Claims claims = mock(Claims.class);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn(JwtTokenProvider.TOKEN_TYPE_ACCESS);
        when(jwtTokenProvider.parseClaims(token)).thenReturn(claims);

        UserPrincipal principal = new UserPrincipal(1L, "0912345678", "Nguyen Van A", "revoked-jti-123", Instant.now().plusSeconds(300));
        when(jwtTokenProvider.getUserPrincipal(claims)).thenReturn(principal);
        when(redisTemplate.hasKey("auth:blacklist:revoked-jti-123")).thenReturn(Boolean.TRUE);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);

        JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
        assertThat(jsonNode.get("status").asInt()).isEqualTo(401);
        assertThat(jsonNode.get("code").asText()).isEqualTo("TOKEN_REVOKED");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Token đã bị thu hồi hoặc đã đăng xuất");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal should set SecurityContext and proceed when token is valid and not blacklisted")
    void doFilterInternal_whenTokenIsValid_shouldAuthenticateAndProceed() throws Exception {
        String token = "valid.access.token";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setServletPath("/api/v1/transfers");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        Claims claims = mock(Claims.class);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn(JwtTokenProvider.TOKEN_TYPE_ACCESS);
        when(jwtTokenProvider.parseClaims(token)).thenReturn(claims);

        UserPrincipal principal = new UserPrincipal(2L, "0987654321", "Tran Thi B", "valid-jti-456", Instant.now().plusSeconds(300));
        when(jwtTokenProvider.getUserPrincipal(claims)).thenReturn(principal);
        when(redisTemplate.hasKey("auth:blacklist:valid-jti-456")).thenReturn(Boolean.FALSE);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(principal);
        assertThat(auth.getAuthorities()).isEqualTo(principal.getAuthorities());
    }

    @Test
    @DisplayName("FilterRegistrationBean should disable servlet filter registration in container")
    void jwtFilterRegistration_shouldBeDisabled() {
        SecurityBeansConfig config = new SecurityBeansConfig();
        FilterRegistrationBean<JwtAuthenticationFilter> registration = config.jwtFilterRegistration(filter);

        assertThat(registration).isNotNull();
        assertThat(registration.isEnabled()).isFalse();
        assertThat(registration.getFilter()).isSameAs(filter);
    }

    @Test
    @DisplayName("AuthenticationEntryPoint in SecurityBeansConfig should return HTTP 401 with required JSON")
    void authenticationEntryPoint_shouldReturn401Json() throws Exception {
        SecurityBeansConfig config = new SecurityBeansConfig();
        AuthenticationEntryPoint entryPoint = config.authenticationEntryPoint(objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallets/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("Full authentication is required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
        assertThat(jsonNode.get("status").asInt()).isEqualTo(401);
        assertThat(jsonNode.get("error").asText()).isEqualTo("Unauthorized");
        assertThat(jsonNode.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Yêu cầu xác thực tài khoản");
    }
}
