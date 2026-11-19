package com.meme.vwallet.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // Dummy controller methods for parameter reflection
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
    @DisplayName("resolveArgument should return parsed Long when X-User-Id is valid")
    void resolveArgument_withValidHeader_shouldReturnUserId() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        when(webRequest.getHeader("X-User-Id")).thenReturn("42");

        Object result = resolver.resolveArgument(param, mavContainer, webRequest, binderFactory);

        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("resolveArgument should trim whitespace and return parsed Long")
    void resolveArgument_withPaddedHeader_shouldReturnUserId() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        when(webRequest.getHeader("X-User-Id")).thenReturn("  100  ");

        Object result = resolver.resolveArgument(param, mavContainer, webRequest, binderFactory);

        assertThat(result).isEqualTo(100L);
    }

    @Test
    @DisplayName("resolveArgument should throw UNAUTHORIZED when X-User-Id is missing (null)")
    void resolveArgument_withMissingHeader_shouldThrowUnauthorized() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        when(webRequest.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveArgument(param, mavContainer, webRequest, binderFactory))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("resolveArgument should throw UNAUTHORIZED when X-User-Id is blank")
    void resolveArgument_withBlankHeader_shouldThrowUnauthorized() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        when(webRequest.getHeader("X-User-Id")).thenReturn("   ");

        assertThatThrownBy(() -> resolver.resolveArgument(param, mavContainer, webRequest, binderFactory))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("resolveArgument should throw UNAUTHORIZED when X-User-Id is not a valid number")
    void resolveArgument_withInvalidHeader_shouldThrowUnauthorized() throws Exception {
        MethodParameter param = getMethodParam("sampleMethodWithLong", Long.class);
        when(webRequest.getHeader("X-User-Id")).thenReturn("abc");

        assertThatThrownBy(() -> resolver.resolveArgument(param, mavContainer, webRequest, binderFactory))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }
}
