package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.RegisterRequest;
import com.meme.vwallet.dto.response.AuthResponse;
import com.meme.vwallet.entity.Notification;
import com.meme.vwallet.entity.Transaction;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.NotificationType;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.repository.LedgerEntryRepository;
import com.meme.vwallet.repository.NotificationRepository;
import com.meme.vwallet.repository.TransactionRepository;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.repository.WithdrawalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class NotificationControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private MockMvc mockMvc;

    private final Map<String, Object> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        redisStore.clear();

        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any());

        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(), any(Duration.class));

        doAnswer(inv -> redisStore.get(inv.getArgument(0)))
                .when(valueOperations).get(anyString());

        doAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null)
                .when(valueOperations).setIfAbsent(anyString(), any(), any(Duration.class));

        doAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null)
                .when(redisTemplate).delete(anyString());

        doAnswer(inv -> redisStore.containsKey(inv.getArgument(0)))
                .when(redisTemplate).hasKey(anyString());

        cleanTestData();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        redisStore.clear();
        notificationRepository.deleteAll();
        withdrawalRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        userRepository.findAll().stream()
                .filter(u -> u.getId() != null && u.getId() > 3L)
                .forEach(u -> {
                    walletRepository.findByUserId(u.getId()).ifPresent(walletRepository::delete);
                    userRepository.delete(u);
                });
    }

    private AuthResponse registerUser(String phone, String name) throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .phoneNumber(phone)
                .fullName(name)
                .password("Password123!")
                .build();

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);
    }

    private Transaction saveTransaction(Wallet sender, Wallet receiver, long amount, TransactionType type) {
        Transaction tx = Transaction.builder()
                .idempotencyKey("NOTIF_TX_" + UUID.randomUUID())
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(amount)
                .type(type)
                .status(TransactionStatus.COMPLETED)
                .description("Test tx")
                .build();
        return transactionRepository.save(tx);
    }

    private Notification saveNotification(User user, Transaction tx, NotificationType type,
                                          String title, String message, boolean read, Instant createdAt) {
        Notification notification = Notification.builder()
                .user(user)
                .transaction(tx)
                .type(type)
                .title(title)
                .message(message)
                .read(read)
                .build();
        notification = notificationRepository.save(notification);
        jdbcTemplate.update(
                "UPDATE notifications SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                notification.getId()
        );
        return notification;
    }

    @Test
    @DisplayName("All /api/v1/notifications endpoints reject unauthenticated requests with 401")
    void notificationEndpoints_unauthenticated_return401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/notifications/1/read"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/notifications/read-all"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/notifications/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Notification inbox pagination, unread filter, unread-count, IDOR mark-read, and bulk read-all work with OSIV=false")
    void notificationsInboxAndReadOperations_authenticated_succeedAndEnforceOwnership() throws Exception {
        AuthResponse authA = registerUser("0916000001", "Notif User A");
        AuthResponse authB = registerUser("0916000002", "Notif User B");

        User userA = userRepository.findById(authA.getUserId()).orElseThrow();
        User userB = userRepository.findById(authB.getUserId()).orElseThrow();
        Wallet systemWallet = walletRepository.findById(1L).orElseThrow();
        Wallet walletA = walletRepository.findByUserId(userA.getId()).orElseThrow();
        Wallet walletB = walletRepository.findByUserId(userB.getId()).orElseThrow();

        Transaction tx1 = saveTransaction(systemWallet, walletA, 500_000L, TransactionType.TOPUP);
        Transaction tx2 = saveTransaction(walletA, walletB, 100_000L, TransactionType.P2P_TRANSFER);
        Transaction tx3 = saveTransaction(walletA, systemWallet, 50_000L, TransactionType.WITHDRAW);

        Instant t1 = Instant.parse("2026-11-26T06:00:00Z");
        Instant t2 = Instant.parse("2026-11-26T06:10:00Z");
        Instant t3 = Instant.parse("2026-11-26T06:20:00Z");

        Notification aRead = saveNotification(userA, tx1, NotificationType.TOPUP_SUCCESS,
                "Nạp tiền thành công", "Bạn đã nạp 500,000 VND", true, t1);
        Notification aUnread1 = saveNotification(userA, tx2, NotificationType.TRANSFER_SENT,
                "Chuyển tiền thành công", "Bạn đã chuyển 100,000 VND", false, t2);
        Notification aUnread2 = saveNotification(userA, tx3, NotificationType.WITHDRAW_SUCCESS,
                "Rút tiền thành công", "Bạn đã rút 50,000 VND", false, t3);
        Notification bUnread = saveNotification(userB, tx2, NotificationType.TRANSFER_RECEIVED,
                "Nhận tiền thành công", "Bạn đã nhận 100,000 VND", false, t3);

        // 1. All notifications for User A (sorted newest first, excludes User B)
        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "false")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.content[0].id", is(aUnread2.getId().intValue())))
                .andExpect(jsonPath("$.content[0].transactionId", is(tx3.getId().intValue())))
                .andExpect(jsonPath("$.content[0].type", is("WITHDRAW_SUCCESS")))
                .andExpect(jsonPath("$.content[0].read", is(false)))
                .andExpect(jsonPath("$.content[2].id", is(aRead.getId().intValue())))
                .andExpect(jsonPath("$.content[2].read", is(true)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(bUnread.getId().intValue()))));

        // 2. Unread-only filter for User A
        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[0].id", is(aUnread2.getId().intValue())))
                .andExpect(jsonPath("$.content[1].id", is(aUnread1.getId().intValue())));

        // 3. Initial unread count for User A = 2
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(2)));

        // 4. IDOR protection: User B cannot mark User A's notification read -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/notifications/{id}/read", aUnread2.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authB.getAccessToken()))
                .andExpect(status().isForbidden());

        // 5. Missing notification -> 404 Not Found
        mockMvc.perform(patch("/api/v1/notifications/{id}/read", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isNotFound());

        // 6. Owner marks one unread notification read -> 200 OK
        mockMvc.perform(patch("/api/v1/notifications/{id}/read", aUnread2.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(aUnread2.getId().intValue())))
                .andExpect(jsonPath("$.transactionId", is(tx3.getId().intValue())))
                .andExpect(jsonPath("$.read", is(true)));

        // 7. Unread count drops to 1
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(1)));

        // 8. Bulk read-all updates remaining 1 unread notification for User A
        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount", is(1)));

        // 9. User A unread count is now 0, User B remains unaffected (1)
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authA.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(0)));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authB.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/stream returns 200 text/event-stream with initial connected event")
    void streamNotifications_authenticated_returnsSseStreamWithConnectedEvent() throws Exception {
        AuthResponse auth = registerUser("0916000003", "SSE Stream User");

        MvcResult result = mockMvc.perform(get("/api/v1/notifications/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:connected");
        assertThat(body).contains("data:connected");
    }
}
