package com.meme.vwallet.service;

import com.meme.vwallet.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private ObjectMapper objectMapper;
    private IdempotencyService idempotencyService;

    record SampleReceipt(String transactionId, Long amount, String status) {}

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new IdempotencyService(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("formatTransferKey correctly produces 'idemp:transfer:{senderUserId}:{idempotencyKey}'")
    void formatTransferKey_formatsCorrectly() {
        String key = idempotencyService.formatTransferKey(123L, "req-uuid-456");
        assertThat(key).isEqualTo("idemp:transfer:123:req-uuid-456");
    }

    @Test
    @DisplayName("acquireLock returns true when key is absent and lock is acquired with 120s TTL")
    void acquireLock_whenAbsent_returnsTrue() {
        String key = "idemp:transfer:123:req-uuid-456";
        when(valueOperations.setIfAbsent(key, "PROCESSING", Duration.ofSeconds(120)))
                .thenReturn(Boolean.TRUE);

        boolean acquired = idempotencyService.acquireLock(key);

        assertThat(acquired).isTrue();
        verify(valueOperations).setIfAbsent(key, "PROCESSING", Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("acquireLock returns false when key is already locked")
    void acquireLock_whenAlreadyLocked_returnsFalse() {
        String key = "idemp:transfer:123:req-uuid-456";
        when(valueOperations.setIfAbsent(key, "PROCESSING", Duration.ofSeconds(120)))
                .thenReturn(Boolean.FALSE);

        boolean acquired = idempotencyService.acquireLock(key);

        assertThat(acquired).isFalse();
        verify(valueOperations).setIfAbsent(key, "PROCESSING", Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("releaseLock calls redisTemplate.delete(key)")
    void releaseLock_deletesKey() {
        String key = "idemp:transfer:123:req-uuid-456";

        idempotencyService.releaseLock(key);

        verify(redisTemplate).delete(key);
    }

    @Test
    @DisplayName("saveReceipt saves serialized JSON string with specified TTL")
    void saveReceipt_savesJsonStringWithTtl() throws Exception {
        String key = "idemp:transfer:123:req-uuid-456";
        SampleReceipt receipt = new SampleReceipt("tx-789", 50000L, "SUCCESS");
        Duration ttl = Duration.ofHours(24);

        idempotencyService.saveReceipt(key, receipt, ttl);

        String expectedJson = objectMapper.writeValueAsString(receipt);
        verify(valueOperations).set(eq(key), eq(expectedJson), eq(ttl));
    }

    @Test
    @DisplayName("getReceipt returns Optional.empty() when key is absent")
    void getReceipt_whenKeyAbsent_returnsEmpty() {
        String key = "idemp:transfer:123:req-uuid-456";
        when(valueOperations.get(key)).thenReturn(null);

        Optional<SampleReceipt> receipt = idempotencyService.getReceipt(key, SampleReceipt.class);

        assertThat(receipt).isEmpty();
        verify(valueOperations).get(key);
    }

    @Test
    @DisplayName("getReceipt returns Optional.empty() when key value is PROCESSING")
    void getReceipt_whenProcessing_returnsEmpty() {
        String key = "idemp:transfer:123:req-uuid-456";
        when(valueOperations.get(key)).thenReturn("PROCESSING");

        Optional<SampleReceipt> receipt = idempotencyService.getReceipt(key, SampleReceipt.class);

        assertThat(receipt).isEmpty();
        verify(valueOperations).get(key);
    }

    @Test
    @DisplayName("getReceipt returns deserialized object when receipt JSON is present")
    void getReceipt_whenJsonPresent_returnsDeserializedObject() throws Exception {
        String key = "idemp:transfer:123:req-uuid-456";
        SampleReceipt expected = new SampleReceipt("tx-789", 50000L, "SUCCESS");
        String json = objectMapper.writeValueAsString(expected);
        when(valueOperations.get(key)).thenReturn(json);

        Optional<SampleReceipt> result = idempotencyService.getReceipt(key, SampleReceipt.class);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expected);
        verify(valueOperations).get(key);
    }

    @Test
    @DisplayName("IdempotencyConflictException has @ResponseStatus(HttpStatus.CONFLICT)")
    void idempotencyConflictException_hasConflictStatus() {
        ResponseStatus annotation = IdempotencyConflictException.class.getAnnotation(ResponseStatus.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(HttpStatus.CONFLICT);
    }
}
