package com.meme.vwallet.controller;

import com.meme.vwallet.service.NotificationService;
import com.meme.vwallet.service.SseEmitterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    @InjectMocks
    private NotificationController notificationController;

    @Test
    @DisplayName("stream explicitly removes emitter before completing with error when initial event send fails")
    void stream_initialSendFails_removesEmitterAndCompletesWithError() throws IOException {
        SseEmitter failingEmitter = mock(SseEmitter.class);
        IOException ioException = new IOException("Broken pipe");
        when(sseEmitterRegistry.register(5L)).thenReturn(failingEmitter);
        doThrow(ioException).when(failingEmitter).send(any(SseEmitter.SseEventBuilder.class));

        SseEmitter returned = notificationController.stream(5L);

        assertThat(returned).isSameAs(failingEmitter);
        verify(sseEmitterRegistry).removeEmitter(5L, failingEmitter);
        verify(failingEmitter).completeWithError(ioException);
    }
}
