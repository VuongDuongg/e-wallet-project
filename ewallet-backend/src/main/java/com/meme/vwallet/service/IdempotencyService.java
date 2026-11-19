package com.meme.vwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String PROCESSING_STATUS = "PROCESSING";
    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(120);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Formats namespaced Redis idempotency key for P2P transfer.
     */
    public String formatTransferKey(Long senderUserId, String idempotencyKey) {
        return "idemp:transfer:" + senderUserId + ":" + idempotencyKey;
    }

    /**
     * Acquires a distributed lock with 120s TTL using SETNX.
     *
     * @param key the Redis idempotency key
     * @return true if the lock was acquired, false if already locked/processed
     */
    public boolean acquireLock(String key) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING_STATUS, DEFAULT_LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases/evicts the idempotency lock key from Redis immediately.
     *
     * @param key the Redis idempotency key
     */
    public void releaseLock(String key) {
        redisTemplate.delete(key);
    }

    /**
     * Stores serialized receipt JSON in Redis with the specified TTL.
     *
     * @param key the Redis idempotency key
     * @param receiptPayload the receipt payload object to store
     * @param ttl the duration to retain the receipt
     */
    public void saveReceipt(String key, Object receiptPayload, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(receiptPayload);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.error("Failed to serialize receipt payload for key {}", key, e);
            throw new RuntimeException("Failed to serialize receipt payload", e);
        }
    }

    /**
     * Retrieves and deserializes the cached receipt if present.
     * Returns Optional.empty() if the key is absent or currently in "PROCESSING" state.
     *
     * @param key the Redis idempotency key
     * @param clazz the expected receipt class
     * @return Optional containing deserialized receipt, or empty
     */
    public <T> Optional<T> getReceipt(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null || PROCESSING_STATUS.equals(value)) {
            return Optional.empty();
        }

        try {
            if (value instanceof String jsonStr) {
                return Optional.ofNullable(objectMapper.readValue(jsonStr, clazz));
            } else if (clazz.isInstance(value)) {
                return Optional.of(clazz.cast(value));
            } else {
                return Optional.ofNullable(objectMapper.convertValue(value, clazz));
            }
        } catch (Exception e) {
            log.error("Failed to deserialize receipt payload for key {}", key, e);
            throw new RuntimeException("Failed to deserialize receipt payload", e);
        }
    }
}
