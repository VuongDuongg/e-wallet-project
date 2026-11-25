# Phase C Design Specification: Client Business APIs, Cash-Out, QR Code & Async Kafka Notifications

- **Project:** VWallet (Core E-Wallet Engine)
- **Module:** `ewallet-backend`
- **Author:** Antigravity AI & VuongDuongg
- **Date:** 2026-11-25
- **Status:** Draft (Awaiting Builder Check & Approval)

---

## 1. Intent & Context

**Phase A** delivered the Double-Entry Ledger, deadlock-free P2P transfer engine, Redis distributed idempotency, and 2-Phase VNPay top-up gateway. **Phase B** secured all endpoints with Spring Security 6, stateless JWT (`ACCESS` / `REFRESH`), single-device Refresh Token Rotation (RTR) with a 30s grace period, zero-fallback `@CurrentUser` resolution, and OpenAPI/Swagger UI with CORS support.

**Phase C** completes the client-facing backend capabilities required by mobile (`ewallet-mobile`) and web clients:
1. **Paginated Transaction History** with direction awareness (`IN` / `OUT`), multi-criteria filtering, and zero N+1 query overhead.
2. **Recipient Lookup by Phone Number** with phone normalization, minimal privacy-safe response, and Redis rate limiting.
3. **Cash-Out (Bank Withdrawal)** reusing `SYSTEM_WALLET`, deterministic pessimistic row locking, `BCrypt` PIN verification, `Idempotency-Key` protection, and Double-Entry Ledger accounting.
4. **QR Code Payment/Receive** using HMAC-SHA256 signed static/dynamic payloads with tamper & expiration verification.
5. **Event-Driven Async Notifications** using Apache Kafka (KRaft), transactional `AFTER_COMMIT` event publishing, persistent DB notification inbox, idempotent consumer handling, and real-time Server-Sent Events (SSE) delivery.

---

## 2. Goals, Non-Goals, & Anti-Goals

### 2.1. Goals
1. **Paginated Transaction History (`GET /api/v1/transactions`):**
   - Return paginated transactions where the authenticated user's wallet is either sender or receiver.
   - Support filtering by `type` (`TOPUP`, `P2P_TRANSFER`, `WITHDRAW`), `status` (`PENDING`, `COMPLETED`, `FAILED`), `direction` (`ALL`, `IN`, `OUT`), and date range (`from`, `to`).
   - Compute clear client-facing direction (`IN` vs `OUT`) and counterparty metadata (`counterpartyPhone`, `counterpartyName`).
2. **Recipient Lookup (`GET /api/v1/users/lookup?phone=...`):**
   - Normalize Vietnamese phone numbers (`+84...` -> `0...`) via `PhoneNumberUtils`.
   - Enforce Redis sliding/fixed-window rate limiting (`ratelimit:lookup:{userId}`) to prevent phone enumeration scraping.
   - Return only safe display fields (`phoneNumber`, `fullName`, `canReceive`, `isSelf`).
3. **Cash-Out / Bank Withdrawal (`POST /api/v1/withdrawals`):**
   - Enforce `Idempotency-Key` header (`idemp:withdraw:{userId}:{key}`) and 6-digit PIN verification.
   - Lock `(SYSTEM_WALLET, userWallet)` in ascending ID order (`min -> max`) with `PESSIMISTIC_WRITE`.
   - Record `Transaction` (`type = WITHDRAW`, `status = COMPLETED`), execute `DEBIT` on user wallet + `CREDIT` on `SYSTEM_WALLET`, and persist bank payout metadata in `withdrawals`.
4. **QR Code Pay/Receive (`POST /api/v1/qr/generate`, `POST /api/v1/qr/parse`):**
   - Generate HMAC-SHA256 signed `vwallet://transfer?...` URI payloads (`STATIC` reusable receive QR or `DYNAMIC` amount-bound expiring QR).
   - Verify QR signature (`MessageDigest.isEqual`) and expiration before resolving live recipient info for transfer confirmation.
5. **Async Notifications (`Kafka` + `DB Inbox` + `SSE`):**
   - Emit domain events strictly after DB transaction commit (`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`).
   - Publish to Apache Kafka topic `vwallet.transaction.completed`; consume idempotently into `notifications` table and push live via `GET /api/v1/notifications/stream` (SSE).
   - Provide inbox APIs (`GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH /api/v1/notifications/{id}/read`, `PATCH /api/v1/notifications/read-all`).

### 2.2. Non-Goals
- Real banking integration (NAPAS 24/7 / VietQR external bank transfer APIs) — Cash-out uses Mock Bank settlement inside `VWallet` (`SYSTEM_WALLET` clearing).
- Multi-currency FX conversion — strictly `VND`.
- Firebase Cloud Messaging (FCM) / APNs mobile push integration — SSE + DB inbox fulfills real-time & offline delivery in Phase C; FCM adapter can subscribe to the same Kafka topic in Phase D.
- Server-side QR PNG image rendering (`zxing`) — backend generates and validates signed QR URI strings; mobile/web clients render QR visually.

### 2.3. Anti-Goals (System Invariants)
- **NO Unaudited Balance Mutations:** Withdrawals MUST pass through `LedgerService.executeDoubleEntry` (`DEBIT` user, `CREDIT` `SYSTEM_WALLET`), preserving $\sum \Delta \text{Balance} = 0$.
- **NO Financial Rollback on Notification Failure:** Kafka broker downtime or SSE disconnect must NEVER cause an already-committed transfer, top-up, or withdrawal to fail or roll back.
- **NO Unbounded Queries or N+1 Fetching:** Transaction history and notifications must always be paginated (capped `size <= 100`) and fetch joined entities efficiently.
- **NO Sensitive Data Leakage:** Lookup and QR parse APIs must never expose `passwordHash`, `pinHash`, internal `wallet.balance`, or another user's private transactions.

---

## 3. Architecture & Approaches Evaluated

| Component | Selected Approach | Rejected Alternative | Trade-off & Justification |
| :--- | :--- | :--- | :--- |
| **Async Notification Pipeline** | **Apache Kafka (KRaft) + `@TransactionalEventListener(AFTER_COMMIT)`** | In-process `@Async` Spring events only | **Selected Approach 1 (User Choice):** Leverages `spring-boot-starter-kafka` already in `pom.xml`, decouples ledger transactions from notification delivery, and survives service restarts via durable Kafka offsets. Adds one lightweight Kafka container to `docker-compose.yml`. |
| **Realtime Push Channel** | **Server-Sent Events (`SseEmitter`) + DB Inbox** | STOMP over WebSocket (`spring-boot-starter-websocket`) | **Selected SSE:** Native HTTP/1.1 streaming compatible with Bearer JWT auth (`JwtAuthenticationFilter`), zero extra protocol handshake, simpler reconnect semantics, backed by persistent `notifications` DB inbox when client is offline. |
| **Cash-Out Settlement** | **Atomic Mock-Bank Settlement (`COMPLETED` under lock + `withdrawals` detail table)** | 2-Phase `PENDING` payout + separate async settlement cron | **Selected Atomic Settlement:** Prevents double-spending without introducing an uncommitted `PENDING` hold state; keeps `TransactionStatus` clean (`COMPLETED`); stores bank fields cleanly in `withdrawals` without altering Phase A `transactions` columns. |
| **QR Code Format** | **HMAC-SHA256 Signed `vwallet://transfer` Payload** | Unsigned JSON/phone string or server-rendered PNG bytes | **Selected Signed Payload:** Zero extra image libraries; prevents client tampering of `amount` or `receiverPhone`; supports static & time-bounded dynamic QR codes natively. |

---

## 4. Detailed Design & Specifications

### 4.1. Database Schema Additions (`schema.sql`)

All DDL statements must remain idempotent (`CREATE TABLE IF NOT EXISTS`) to preserve `spring.sql.init.mode: always` restart resilience.

#### 4.1.1. `withdrawals` Table
Stores bank payout metadata linked 1-to-1 with a `WITHDRAW` record in `transactions`:
```sql
CREATE TABLE IF NOT EXISTS withdrawals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id BIGINT NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    bank_code VARCHAR(20) NOT NULL,
    bank_account_number VARCHAR(30) NOT NULL,
    account_holder_name VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    bank_ref VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wd_tx FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT fk_wd_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_wd_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 4.1.2. `notifications` Table
Persistent inbox for async notifications, enforcing idempotency per `(user_id, transaction_id, type)` for Kafka at-least-once delivery:
```sql
CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    transaction_id BIGINT NULL,
    type VARCHAR(30) NOT NULL,
    title VARCHAR(150) NOT NULL,
    message VARCHAR(500) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_notif_tx FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT uk_notif_idemp UNIQUE (user_id, transaction_id, type),
    INDEX idx_notif_user_read_created (user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
- `NotificationType` enum values: `TOPUP_SUCCESS`, `TRANSFER_SENT`, `TRANSFER_RECEIVED`, `WITHDRAW_SUCCESS`.

#### 4.1.3. `transactions` Composite Indexes (History Query Optimization)
Add composite indexes directly inside the `CREATE TABLE IF NOT EXISTS transactions (...)` definition in `schema.sql` (valid in both MySQL 8.0 and H2 `MODE=MySQL` without needing `CREATE INDEX IF NOT EXISTS`, which MySQL 8.0 does not support) so paginated `sender_wallet_id` / `receiver_wallet_id` queries sorted by `created_at DESC` avoid full scans:
```sql
INDEX idx_tx_sender_created (sender_wallet_id, created_at),
INDEX idx_tx_receiver_created (receiver_wallet_id, created_at)
```
*(For existing local MySQL Docker instances initialized before Phase C, run `ALTER TABLE transactions ADD INDEX ...` once or recreate the dev volume).*

### 4.2. Redis Key Namespace Additions

| Redis Key Pattern | Type | Value | TTL | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `idemp:withdraw:{userId}:{idempotencyKey}` | String | `"PROCESSING"` or serialized `WithdrawalResponse` JSON | 120s (lock) / 24h (receipt) | Prevents duplicate cash-out execution on retries/double-taps |
| `ratelimit:lookup:{userId}` | Counter (String/Long) | Request count in current window | 60s | Limits recipient phone lookup to max 30 requests/minute per user |

### 4.3. Configuration Properties (`application.yaml`)

Add configurable Phase C properties with safe dev fallbacks:
- `spring.jpa.open-in-view`: `false` (**Mandatory:** prevents SSE 30-minute HTTP streams from holding MySQL connections open across the entire request lifecycle, avoiding DB connection pool exhaustion).
- `vwallet.withdraw.min-amount`: `10000` (VND)
- `vwallet.withdraw.max-amount`: `50000000` (VND)
- `vwallet.qr.secret`: `"${QR_SECRET:dGhpc19pc19hX3Zlcnlfc2VjdXJlXzI1Nl9iaXRfcXJfZGV2X3NlY3JldF9rZXkh}"`
- `vwallet.qr.dynamic-ttl-seconds`: `900` (15 minutes)
- `vwallet.qr.min-amount`: `1000` (VND)
- `vwallet.qr.max-amount`: `50000000` (VND)
- `vwallet.lookup.rate-limit-per-minute`: `30`
- `vwallet.sse.max-emitters-per-user`: `5`
- `vwallet.kafka.topics.transaction-completed`: `"vwallet.transaction.completed"`
- `spring.kafka.bootstrap-servers`: `"${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}"`
- `spring.kafka.producer.key-serializer`: `org.apache.kafka.common.serialization.StringSerializer`
- `spring.kafka.producer.value-serializer`: `org.apache.kafka.common.serialization.StringSerializer`
- `spring.kafka.consumer.group-id`: `"vwallet-notification-group"`
- `spring.kafka.consumer.auto-offset-reset`: `"earliest"`
- `spring.kafka.consumer.key-deserializer`: `org.apache.kafka.common.serialization.StringDeserializer`
- `spring.kafka.consumer.value-deserializer`: `org.apache.kafka.common.serialization.StringDeserializer`

Add single-node Kafka KRaft service (`apache/kafka:3.9.0`) to `docker/docker-compose.yml` exposing port `9092`.

---

### 4.4. Feature 1: Paginated Transaction History

- **Endpoint:** `GET /api/v1/transactions` (Authenticated via `@CurrentUser Long userId`)
- **Query Parameters:**
  - `page` (`int`, default `0`, min `0`)
  - `size` (`int`, default `20`, min `1`, max `100`)
  - `type` (`TransactionType`, optional: `TOPUP`, `P2P_TRANSFER`, `WITHDRAW`)
  - `status` (`TransactionStatus`, optional: `PENDING`, `COMPLETED`, `FAILED`)
  - `direction` (`String`, optional: `ALL` (default), `IN`, `OUT`)
  - `from` (`Instant`, optional ISO-8601)
  - `to` (`Instant`, optional ISO-8601)
- **Business Logic (`TransactionQueryService` / `PaymentService`):**
  1. Look up authenticated user's `Wallet` (`myWalletId = wallet.getId()`).
  2. Validate `from <= to` if both provided (else `400 Bad Request`).
  3. Query `TransactionRepository` extending `JpaSpecificationExecutor<Transaction>` with an `@EntityGraph(attributePaths = {"senderWallet", "senderWallet.user", "receiverWallet", "receiverWallet.user"})` override on `findAll(Specification<Transaction>, Pageable)` (never raw `JOIN FETCH` without `countQuery`, avoiding Hibernate `HHH90003004` in-memory pagination and N+1 queries) enforcing:
     - `direction == ALL`: `(senderWallet.id == myWalletId OR receiverWallet.id == myWalletId)`
     - `direction == IN`: `receiverWallet.id == myWalletId`
     - `direction == OUT`: `senderWallet.id == myWalletId`
     - Plus optional `type`, `status`, `createdAt >= from`, `createdAt <= to`.
     - Sort descending by `createdAt DESC, id DESC`.
  4. Map each `Transaction` to `TransactionHistoryItemResponse`:
     - `direction`: `"IN"` if `receiverWallet.id.equals(myWalletId)`, else `"OUT"`.
     - `counterpartyPhone` & `counterpartyName`:
       - For `P2P_TRANSFER`: partner wallet owner's `phoneNumber` & `fullName`.
       - For `TOPUP`: `"SYSTEM"` / `"VNPay Top-up"`.
       - For `WITHDRAW`: `"BANK"` / `"Rút tiền ngân hàng"`.
- **Response (`200 OK`):**
  ```json
  {
    "content": [
      {
        "transactionId": 105,
        "type": "P2P_TRANSFER",
        "status": "COMPLETED",
        "direction": "OUT",
        "amount": 100000,
        "counterpartyPhone": "0987654321",
        "counterpartyName": "Tran Thi B",
        "description": "Chuyen tien an trua",
        "externalRef": null,
        "createdAt": "2026-11-25T12:00:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
  ```

---

### 4.5. Feature 2: Recipient Lookup by Phone

- **Endpoint:** `GET /api/v1/users/lookup?phone={phone}` (Authenticated via `@CurrentUser Long currentUserId`)
- **Validation & Rate Limiting:**
  1. Normalize `phone` via `PhoneNumberUtils.normalize(phone)` and validate against `^(\\+84|0)[35789][0-9]{8}$` (invalid format -> `400 Bad Request`, `INVALID_PHONE_FORMAT`).
  2. Block system reserve account: if normalized phone equals `"0000000000"` (`SYSTEM_USER`), reject immediately with `404 Not Found` (`USER_NOT_FOUND`).
  3. Increment Redis counter `ratelimit:lookup:{currentUserId}` (`opsForValue().increment(key)`). Set TTL `60s` whenever `count == 1` OR `redisTemplate.getExpire(key)` has no expiration (`ttl == null || ttl < 0`, guarding against orphaned keys without TTL). If `count > maxPerMinute` -> throw `RateLimitExceededException` (`429 Too Many Requests`, `RATE_LIMIT_EXCEEDED`).
  4. Find target `User` by normalized phone (`404 Not Found`, `USER_NOT_FOUND` if absent or `targetUser.getId() == 1L`) and their `Wallet`.
  5. Compute `isSelf = targetUser.getId().equals(currentUserId)` and `canReceive = !isSelf && "ACTIVE".equals(targetUser.getStatus()) && targetWallet.getStatus() == WalletStatus.ACTIVE`.
- **Response (`200 OK`):**
  ```json
  {
    "phoneNumber": "0987654321",
    "fullName": "Tran Thi B",
    "isSelf": false,
    "canReceive": true
  }
  ```

---

### 4.6. Feature 3: Cash-Out (Withdrawal to Bank Account)

- **Endpoint:** `POST /api/v1/withdrawals` (Authenticated via `@CurrentUser Long userId`)
- **Headers:**
  - `Authorization: Bearer <accessToken>`
  - `Idempotency-Key: <unique-string>` (Mandatory)
- **Request Body (`WithdrawalRequest`):**
  - `bankCode`: `@NotBlank`, `@Pattern(regexp = "^[A-Z0-9_]{2,20}$")` (e.g., `"VCB"`, `"TCB"`, `"MB"`)
  - `bankAccountNumber`: `@NotBlank`, `@Pattern(regexp = "^[0-9]{6,20}$")`
  - `accountHolderName`: `@NotBlank`, `@Size(min = 2, max = 100)`
  - `amount`: `@NotNull`, positive, bounded `[minAmount, maxAmount]` (`10,000` - `50,000,000` VND)
  - `pin`: `@NotBlank`, `@Pattern(regexp = "^[0-9]{6}$")`
  - `description`: optional (`max 255`)
- **Execution Flow (`WithdrawalService`):**
  1. **Out-of-Transaction Idempotency:** Check `idemp:withdraw:{userId}:{idempotencyKey}` receipt in Redis -> return cached `WithdrawalResponse` (`200 OK`) if present. Acquire lock (`SETNX`, 120s) -> throw `IdempotencyConflictException` (`409 Conflict`) if locked.
  2. **Transactional Ledger & Payout Execution (`executeWithdrawTx`):**
     - Load user (`ACTIVE` check -> `403 USER_LOCKED` if locked) and fetch `userWalletId` via a lightweight scalar query (`walletRepository.findIdByUserId(userId)` -> `400 WalletException` if not found), avoiding loading the `Wallet` entity into Hibernate L1 cache before locking.
     - Verify PIN via `userService.verifyPin(userId, request.getPin())` (`PIN_NOT_SET` / `INVALID_PIN`).
     - Lock `min(SYSTEM_WALLET_ID, userWalletId)` then `max(SYSTEM_WALLET_ID, userWalletId)` via `walletRepository.findByIdForUpdate`.
     - Check `lockedUserWallet.getStatus() == WalletStatus.ACTIVE` (`400 WalletException`) and `lockedUserWallet.getBalance() >= amount` (`400 INSUFFICIENT_BALANCE`) **after** acquiring the `FOR UPDATE` row lock.
     - Compute DB `Transaction.idempotencyKey` as lowercase hex `SHA-256("WD:" + userId + ":" + idempotencyKey)` (fixed 64 chars, strictly matching `VARCHAR(64)` regardless of client header length and preventing `DataTruncation`).
     - Create `Transaction` (`type = WITHDRAW`, `status = COMPLETED`, `senderWallet = lockedUserWallet`, `receiverWallet = lockedSystemWallet`, `idempotencyKey = sha256Hex`, `externalRef = "MOCK_BANK_" + UUID`).
     - Call `ledgerService.executeDoubleEntry(tx, lockedUserWallet, lockedSystemWallet, amount)` (`DEBIT` user, `CREDIT` `SYSTEM_WALLET`).
     - Save `Withdrawal` record with `bankCode`, `bankAccountNumber`, `accountHolderName`, `amount`, `bankRef`.
     - Publish Spring `TransactionCompletedEvent` (will fire to Kafka `AFTER_COMMIT`).
  3. **Post-Commit:** Save `WithdrawalResponse` receipt in Redis (TTL 24h); in `catch`, release idempotency lock so failed attempts (wrong PIN, insufficient funds) can be retried immediately.
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "withdrawalId": 12,
    "transactionId": 106,
    "bankCode": "VCB",
    "bankAccountNumber": "1234567890",
    "accountHolderName": "NGUYEN VAN A",
    "amount": 200000,
    "status": "COMPLETED",
    "bankRef": "MOCK_BANK_8f4a21c0",
    "balanceAfter": 200000,
    "createdAt": "2026-11-25T12:15:00Z"
  }
  ```

---

### 4.7. Feature 4: QR Code Generation & Parsing

- **QR Format Specification:**
  - Compact URI format: `vwallet://transfer?d=<base64url-payload>&sig=<hmac-sha256-hex>`
  - Decoded JSON payload (`d`):
    ```json
    {
      "v": 1,
      "type": "DYNAMIC",
      "phone": "0901234567",
      "amount": 150000,
      "desc": "Tien ca phe",
      "exp": 1795610000
    }
    ```
  - `sig`: Lowercase hex `HMAC-SHA256(vwallet.qr.secret, base64url-payload)`. Payload `d` is encoded using `Base64.getUrlEncoder().withoutPadding()`. Verified with constant-time `MessageDigest.isEqual`.
- **Endpoints (`QrController`):**
  1. `POST /api/v1/qr/generate` (Authenticated via `@CurrentUser Long userId`)
     - Request: `{ "type": "STATIC" | "DYNAMIC", "amount": Long (required if DYNAMIC, null if STATIC), "description": String (optional, @Size(max = 255)) }`.
     - Verifies authenticated user is `"ACTIVE"` (`403 USER_LOCKED`) and their wallet is `WalletStatus.ACTIVE` (`400 WalletException`) before issuing QR.
     - Validates `DYNAMIC` has `amount` bounded in `[vwallet.qr.min-amount, vwallet.qr.max-amount]` (`1,000` - `50,000,000` VND, `400 Bad Request` otherwise) and `description <= 255` chars; `STATIC` forces `amount = null, exp = null`.
     - Reads authenticated user's `phoneNumber` & `fullName`, encodes `d` via `Base64.getUrlEncoder().withoutPadding()`, signs `sig`, returns:
       ```json
       {
         "qrContent": "vwallet://transfer?d=eyJ2IjoxLC...&sig=9f8a...",
         "type": "DYNAMIC",
         "receiverPhone": "0901234567",
         "receiverName": "Nguyen Van A",
         "amount": 150000,
         "description": "Tien ca phe",
         "expiresAt": "2026-11-25T12:45:00Z"
       }
       ```
  2. `POST /api/v1/qr/parse` (Authenticated via `@CurrentUser Long currentUserId`)
     - Request: `{ "qrContent": "vwallet://transfer?d=...&sig=..." }`.
     - Verifies URI scheme, query parameters `d` and `sig`, and HMAC-SHA256 signature (`400 INVALID_QR_CODE` on any mismatch or malformed payload).
     - Checks `exp` if present; if `Instant.now().getEpochSecond() > exp`, throws `400 EXPIRED_QR_CODE`.
     - Rejects `SYSTEM_USER` (`"0000000000"` / `id = 1L`) with `404 USER_NOT_FOUND`.
     - Looks up live recipient user & wallet status in DB (`404 USER_NOT_FOUND` if removed) and returns:
       ```json
       {
         "type": "DYNAMIC",
         "receiverPhone": "0901234567",
         "receiverName": "Nguyen Van A",
         "amount": 150000,
         "description": "Tien ca phe",
         "isSelf": false,
         "canTransfer": true,
         "expiresAt": "2026-11-25T12:45:00Z"
       }
       ```
     - Client confirms on screen and calls existing `POST /api/v1/transfers` with PIN and `Idempotency-Key`.

---

### 4.8. Feature 5: Kafka Event-Driven Async Notifications & SSE

#### 4.8.1. Event Schema (`TransactionCompletedEvent`)
Published on topic `vwallet.transaction.completed`:
```json
{
  "eventId": "uuid",
  "transactionId": 105,
  "transactionType": "P2P_TRANSFER",
  "senderUserId": 2,
  "senderPhone": "0901234567",
  "senderName": "Nguyen Van A",
  "receiverUserId": 3,
  "receiverPhone": "0987654321",
  "receiverName": "Tran Thi B",
  "amount": 100000,
  "description": "Chuyen tien an trua",
  "occurredAt": "2026-11-25T12:00:00Z"
}
```
- **Producer Integration Points:**
  - `TransferService.executeTransferTx` -> publishes Spring domain event (`P2P_TRANSFER`).
  - `PaymentService.processWebhook` (when `vnp_ResponseCode == "00"` completes top-up) -> publishes Spring domain event (`TOPUP`).
  - `WithdrawalService.executeWithdrawTx` -> publishes Spring domain event (`WITHDRAW`).
- **Transactional Outbox Guarantee (Pragmatic AFTER_COMMIT Listener):**
  - `TransactionEventKafkaPublisher` listens with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, serializes `TransactionCompletedEvent` to a JSON string using Jackson `ObjectMapper`, and calls `kafkaTemplate.send(topic, txId.toString(), payloadJson)` via `StringSerializer` (avoiding Spring Boot 4 / Jackson 3 `JsonSerializer` class-compatibility pitfalls) with asynchronous failure logging (never fails the financial HTTP response).

#### 4.8.2. Consumer & Persistence (`NotificationConsumer`)
- `@KafkaListener(topics = "${vwallet.kafka.topics.transaction-completed}", groupId = "${spring.kafka.consumer.group-id}")` receives `String payloadJson` via `StringDeserializer` and deserializes into `TransactionCompletedEvent` using `ObjectMapper`.
- Maps event -> 1 or 2 `Notification` entities:
  - `P2P_TRANSFER`: `TRANSFER_SENT` (`-amount` to sender) + `TRANSFER_RECEIVED` (`+amount` to receiver).
  - `TOPUP`: `TOPUP_SUCCESS` (`+amount` to receiver).
  - `WITHDRAW`: `WITHDRAW_SUCCESS` (`-amount` to sender).
- **Isolated Idempotent Persistence:** Persist each notification via an independent transactional boundary (`NotificationPersistenceService.saveIfAbsent(...)` with `@Transactional(propagation = Propagation.REQUIRES_NEW)` or non-transactional per-item repository call) that checks `existsByUserIdAndTransactionIdAndType(...)` and catches `DataIntegrityViolationException` independently per record. This guarantees a duplicate constraint hit on notification #1 (`TRANSFER_SENT`) never marks the Hibernate transaction rollback-only and never blocks notification #2 (`TRANSFER_RECEIVED`).
- Pushes `NotificationResponse` JSON immediately to all active SSE connections for that `userId`.

#### 4.8.3. Notification REST & SSE Endpoints (`NotificationController`)
1. `GET /api/v1/notifications?page=0&size=20&unreadOnly=false` -> Returns `PageResponse<NotificationResponse>`.
2. `GET /api/v1/notifications/unread-count` -> Returns `{ "unreadCount": 3 }`.
3. `PATCH /api/v1/notifications/{id}/read` -> Marks notification read; verifies `notification.getUser().getId().equals(userId)` (`403 Forbidden` on IDOR attempt, `404` if missing).
4. `PATCH /api/v1/notifications/read-all` -> Updates all unread rows for `userId`, returns `{ "updatedCount": 3 }`.
5. `GET /api/v1/notifications/stream` (`produces = TEXT_EVENT_STREAM_VALUE`) -> Registers `SseEmitter` (30-minute timeout) in concurrent `SseEmitterRegistry` (`ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>`), enforces `vwallet.sse.max-emitters-per-user` (default `5`, closing and evicting the oldest emitter when exceeded), removes on completion/timeout/error, and sends an initial `connected` event.
   - `SecurityBeansConfig` must explicitly configure `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` inside `authorizeHttpRequests` so SSE async completion/timeout dispatches do not trigger spurious `401 Unauthorized` / `403 Forbidden` responses.

---

## 5. Error Handling & Security Compliance

- **New Exception / Error Codes (mapped in `GlobalExceptionHandler`):**
  - `INVALID_PHONE_FORMAT` (`400 Bad Request`)
  - `INVALID_QR_CODE` (`400 Bad Request`)
  - `EXPIRED_QR_CODE` (`400 Bad Request`)
  - `RATE_LIMIT_EXCEEDED` (`429 Too Many Requests`)
- **Security Rules:**
  - All Phase C endpoints (`/api/v1/transactions`, `/api/v1/users/lookup`, `/api/v1/withdrawals`, `/api/v1/qr/**`, `/api/v1/notifications/**`) require authenticated JWT (`type == "ACCESS"`). No new public endpoints are introduced.
  - Configure `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` in `SecurityBeansConfig` to allow Spring MVC SSE async timeout/completion dispatches safely after initial JWT authentication.
  - Block `SYSTEM_USER` (`id = 1L` / `"0000000000"`) from being resolved as a recipient in `UserLookupService`, `QrCodeService` (parse), and `TransferService` (P2P transfers).
  - All user-owned resources (`transactions`, `notifications`) enforce `@CurrentUser Long userId` ownership checks in `@Transactional(readOnly = true)` / `@Transactional` service methods.

---

## 6. Testing Strategy

1. **Unit Tests (Mockito):**
   - `TransactionQueryServiceTest`: `IN`/`OUT`/`ALL` direction mapping, date/type/status filters, page size bounds, counterparty resolution.
   - `UserLookupServiceTest`: phone normalization, `isSelf` vs `canReceive`, Redis rate limit (`429`), not found (`404`).
   - `WithdrawalServiceTest`: idempotency cache hit & `409` lock conflict, `PIN_NOT_SET`/`INVALID_PIN`, `INSUFFICIENT_BALANCE`, `min -> max` lock ordering, `DEBIT`/`CREDIT` double-entry + `Withdrawal` persistence, lock release on failure.
   - `QrCodeServiceTest`: static vs dynamic QR generation, HMAC tamper rejection (`INVALID_QR_CODE`), expiry rejection (`EXPIRED_QR_CODE`), live user status resolution.
   - `NotificationServiceTest` & `NotificationConsumerTest`: event-to-notification mapping (`TOPUP`, `P2P`, `WITHDRAW`), duplicate Kafka event idempotency, IDOR check on `markAsRead` (`403`), SSE push & dead-emitter cleanup.
2. **Integration Tests (`@SpringBootTest` + MockMvc + H2):**
   - Test config disables live Kafka broker dependency (`spring.kafka.listener.auto-startup=false` + `@MockitoBean KafkaTemplate`) for fast deterministic suite execution, plus dedicated unit/integration verification of `AFTER_COMMIT` event publishing and `NotificationConsumer` execution.
   - Verify full regression safety: Phase A (102) + Phase B (150) = 252 existing tests remain 100% green alongside all new Phase C tests.
