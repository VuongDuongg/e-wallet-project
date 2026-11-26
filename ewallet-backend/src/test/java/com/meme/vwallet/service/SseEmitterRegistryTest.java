package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.entity.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SseEmitterRegistryTest {

    private SseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry();
    }

    @Test
    @DisplayName("registering 6th emitter for same userId evicts and completes oldest emitter, keeping count <= 5")
    void register_sixthEmitter_evictsAndCompletesOldest() {
        List<SseEmitter> mockedEmitters = new ArrayList<>();
        SseEmitterRegistry customRegistry = new SseEmitterRegistry() {
            @Override
            protected SseEmitter createEmitter() {
                SseEmitter emitter = mock(SseEmitter.class);
                mockedEmitters.add(emitter);
                return emitter;
            }
        };

        for (int i = 0; i < 6; i++) {
            customRegistry.register(10L);
        }

        assertThat(mockedEmitters).hasSize(6);
        assertThat(customRegistry.getActiveEmitterCount(10L)).isEqualTo(5);

        verify(mockedEmitters.get(0)).complete();
        for (int i = 1; i < 6; i++) {
            verify(mockedEmitters.get(i), times(0)).complete();
        }
    }

    @Test
    @DisplayName("sendNotification sends notification event to active emitters and removes dead/throwing emitters")
    void sendNotification_sendsEventAndRemovesDeadEmitters() throws IOException {
        List<SseEmitter> created = new ArrayList<>();
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        SseEmitter deadEmitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        created.add(healthyEmitter);
        created.add(deadEmitter);

        SseEmitterRegistry customRegistry = new SseEmitterRegistry() {
            private int idx = 0;

            @Override
            protected SseEmitter createEmitter() {
                return created.get(idx++);
            }
        };

        customRegistry.register(20L);
        customRegistry.register(20L);
        assertThat(customRegistry.getActiveEmitterCount(20L)).isEqualTo(2);

        NotificationResponse payload = NotificationResponse.builder()
                .id(1L)
                .transactionId(99L)
                .type(NotificationType.TOPUP_SUCCESS)
                .title("Nạp tiền thành công")
                .message("Bạn đã nạp 500,000 VND")
                .read(false)
                .createdAt(Instant.now())
                .build();

        assertThatCode(() -> customRegistry.sendNotification(20L, payload))
                .doesNotThrowAnyException();

        verify(healthyEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(deadEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(customRegistry.getActiveEmitterCount(20L)).isEqualTo(1);
    }

    @Test
    @DisplayName("real SseEmitter completed externally is removed on sendNotification")
    void sendNotification_completedRealEmitter_isRemovedCleanly() {
        SseEmitter emitter = registry.register(30L);
        assertThat(registry.getActiveEmitterCount(30L)).isEqualTo(1);

        emitter.complete();

        NotificationResponse payload = NotificationResponse.builder()
                .id(2L)
                .transactionId(100L)
                .type(NotificationType.WITHDRAW_SUCCESS)
                .title("Rút tiền thành công")
                .message("Bạn đã rút 200,000 VND")
                .read(false)
                .createdAt(Instant.now())
                .build();

        assertThatCode(() -> registry.sendNotification(30L, payload))
                .doesNotThrowAnyException();

        assertThat(registry.getActiveEmitterCount(30L)).isZero();
    }
}
