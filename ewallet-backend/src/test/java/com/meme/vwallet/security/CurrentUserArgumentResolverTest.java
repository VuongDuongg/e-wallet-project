package com.meme.vwallet.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserArgumentResolverTest {

    private CurrentUserArgumentResolver resolver;

    @Mock
    private NativeWebRequest webRequest;

    @Mock
    private ModelAndViewContainer mavContainer;

    @Mock
    private WebDataBinderFactory binderFactory;

    @SuppressWarnings("unused")
    private void sampleMethodWithLong(@CurrentUser Long userId) {}

    @SuppressWarnings("unused")
    private void sampleMethodWithPrimitiveLong(@CurrentUser long userId) {}

    @SuppressWarnings("unused")
    private void sampleMethodWithoutAnnotation(Long userId) {}

    @SuppressWarnings("unused")
    private void sampleMethodWithWrongType(@CurrentUser String userId) {}

    @BeforeEach
    void setUp() {
        resolver = new CurrentUserArgumentResolver();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MethodParameter getMethodParam(String methodName, Class<?> paramType) throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod(methodName, paramType);
        return new MethodParameter(method, 0);
    }

    @Test
    @DisplayName("supportsParameter should return true for @CurrentUser Long")
    void supportsParameter_withCurrentUserLong_shouldReturnTrue() throws NoSuchMethodException {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        assertThat(resolver.supportsParameter(param)).isTrue();
    }

    @Test
    @DisplayName("supportsParameter should return true for @CurrentUser primitive long")
    void supportsParameter_withCurrentUserPrimitiveLong_shouldReturnTrue() throws NoSuchMethodException {
        MethodParameter param = getMethodParam("sampleMethodWithPrimitiveLong", long.class);
        assertThat(resolver.supportsParameter(param)).isTrue();
    }

    @Test
    @DisplayName("supportsParameter should return false without @CurrentUser")
    void supportsParameter_withoutAnnotation_shouldReturnFalse() throws NoSuchMethodException {
        MethodParameter param = getMethodParam("sampleMethodWithoutAnnotation", Long.class);
        assertThat(resolver.supportsParameter(param)).isFalse();
    }

    @Test
    @DisplayName("supportsParameter should return false for @CurrentUser with non-Long type")
    void supportsParameter_withWrongType_shouldReturnFalse() throws NoSuchMethodException {
        MethodParameter param = getMethodParam("sampleMethodWithWrongType", String.class);
        assertThat(resolver.supportsParameter(param)).isFalse();
    }

    @Test
    @DisplayName("resolveArgument should return userId from authenticated UserPrincipal in SecurityContextHolder")
    void resolveArgument_withAuthenticatedUserPrincipal_shouldReturnUserId() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        UserPrincipal principal = new UserPrincipal(42L, "0912345678", "Nguyen Van A", "test-jti", Instant.now().plusSeconds(300));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Object result = resolver.resolveArgument(param, mavContainer, webRequest, binderFactory);

        assertThat(result).isEqualTo(42L);
        verifyNoInteractions(webRequest);
    }

    @Test
    @DisplayName("resolveArgument should completely ignore X-User-Id header and return authenticated userId")
    void resolveArgument_withUserPrincipalAndDifferentXUserIdHeader_shouldReturnPrincipalUserIdAndIgnoreHeader() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        UserPrincipal principal = new UserPrincipal(100L, "0987654321", "Tran Thi B", "jti-100", Instant.now().plusSeconds(300));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Even if X-User-Id is passed as 999, resolver strictly uses principal's 100L
        Object result = resolver.resolveArgument(param, mavContainer, webRequest, binderFactory);

        assertThat(result).isEqualTo(100L);
        verifyNoInteractions(webRequest);
    }

    @Test
    @DisplayName("resolveArgument should throw UNAUTHORIZED when unauthenticated, ignoring any X-User-Id header")
    void resolveArgument_whenUnauthenticated_shouldThrowUnauthorizedEvenWithHeader() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> resolver.resolveArgument(param, mavContainer, webRequest, binderFactory))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("Unauthenticated request");
                });
    }

    @Test
    @DisplayName("resolveArgument should throw UNAUTHORIZED when authentication is null")
    void resolveArgument_whenAuthenticationIsNull_shouldThrowUnauthorized() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        SecurityContextHolder.getContext().setAuthentication(null);

        assertThatThrownBy(() -> resolver.resolveArgument(param, mavContainer, webRequest, binderFactory))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("Unauthenticated request");
                });
    }

    @Test
    @DisplayName("resolveArgument should throw UNAUTHORIZED when principal is not UserPrincipal")
    void resolveArgument_whenPrincipalIsNotUserPrincipal_shouldThrowUnauthorized() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "anonymousUser", null
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> resolver.resolveArgument(param, mavContainer, webRequest, binderFactory))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("Unauthenticated request");
                });
    }
}
