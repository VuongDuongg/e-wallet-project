package com.meme.vwallet.service;

import com.meme.vwallet.dto.response.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory registry of active SSE emitters per user.
 * Enforces a per-user connection cap by evicting and completing the oldest emitter.
 */
@Slf4j
@Service
public class SseEmitterRegistry {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    @Value("${vwallet.sse.max-emitters-per-user:5}")
    private int maxEmittersPerUser = 5;

    @Value("${vwallet.sse.timeout-ms:1800000}")
    private long emitterTimeoutMs = 1_800_000L;

    public SseEmitter register(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        SseEmitter emitter = createEmitter();
        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> {
            removeEmitter(userId, emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore already completed emitter
            }
        });
        emitter.onError(ex -> removeEmitter(userId, emitter));

        List<SseEmitter> evicted = new ArrayList<>();
        int cap = Math.max(1, maxEmittersPerUser);
        while (true) {
            CopyOnWriteArrayList<SseEmitter> list = emittersByUser.computeIfAbsent(
                    userId, id -> new CopyOnWriteArrayList<>());
            synchronized (list) {
                if (emittersByUser.get(userId) != list) {
                    continue;
                }
                while (list.size() >= cap && !list.isEmpty()) {
                    evicted.add(list.remove(0));
                }
                list.add(emitter);
                break;
            }
        }

        for (SseEmitter old : evicted) {
            try {
                old.complete();
            } catch (Exception ex) {
                log.debug("Failed to complete evicted SSE emitter for userId={}", userId, ex);
            }
        }
        return emitter;
    }

    public void sendNotification(Long userId, NotificationResponse payload) {
        if (userId == null || payload == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = emittersByUser.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                        .name("notification")
                        .data(payload);
                if (payload.getId() != null) {
                    eventBuilder.id(payload.getId().toString());
                }
                emitter.send(eventBuilder);
            } catch (Exception ex) {
                log.debug("Removing dead SSE emitter for userId={}: {}", userId, ex.getMessage());
                removeEmitter(userId, emitter);
                try {
                    emitter.completeWithError(ex);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    public int getActiveEmitterCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        CopyOnWriteArrayList<SseEmitter> list = emittersByUser.get(userId);
        return list != null ? list.size() : 0;
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(emitterTimeoutMs);
    }

    public void removeEmitter(Long userId, SseEmitter emitter) {
        if (userId == null || emitter == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = emittersByUser.get(userId);
        if (list == null) {
            return;
        }
        synchronized (list) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByUser.remove(userId, list);
            }
        }
    }
}
