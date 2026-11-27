package com.meme.vwallet.controller;

import com.meme.vwallet.dto.response.NotificationResponse;
import com.meme.vwallet.dto.response.PageResponse;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.notification.NotificationService;
import com.meme.vwallet.service.notification.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * REST inbox and SSE real-time notification stream endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final SseEmitterRegistry sseEmitterRegistry;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> getNotifications(
            @CurrentUser Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        return ResponseEntity.ok(notificationService.getNotifications(userId, page, size, unreadOnly));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@CurrentUser Long userId) {
        return ResponseEntity.ok(Map.of("unreadCount", notificationService.getUnreadCount(userId)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markAsRead(
            @CurrentUser Long userId,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(notificationService.markAsRead(userId, id));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllAsRead(@CurrentUser Long userId) {
        return ResponseEntity.ok(Map.of("updatedCount", notificationService.markAllAsRead(userId)));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser Long userId) {
        SseEmitter emitter = sseEmitterRegistry.register(userId);
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("connected"));
        } catch (IOException ex) {
            log.debug("Failed to send initial connected SSE event for userId={}", userId, ex);
            sseEmitterRegistry.removeEmitter(userId, emitter);
            emitter.completeWithError(ex);
        }
        return emitter;
    }
}
