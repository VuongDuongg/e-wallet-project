# Phase C Client Business APIs, Cash-Out, QR Code & Async Kafka Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the complete Phase C client-facing capabilities in `ewallet-backend`: paginated transaction history, rate-limited phone recipient lookup, atomic Cash-out withdrawals, HMAC-SHA256 signed QR generation/parsing, and Apache Kafka + DB Inbox + SSE asynchronous notifications.

**Architecture:** Builds cleanly on Phase A's Double-Entry Ledger (`LedgerService`, `SYSTEM_WALLET id=1`, deterministic `min -> max` pessimistic row locking) and Phase B's stateless JWT security (`@CurrentUser`). Financial commits publish flat `TransactionCompletedEvent` DTOs via `@TransactionalEventListener(AFTER_COMMIT)` over Kafka (`StringSerializer`/`StringDeserializer` + Jackson `ObjectMapper`), consumed into an idempotent `notifications` DB inbox (isolated per-row saves) and pushed to capped per-user `SseEmitter` streams with `spring.jpa.open-in-view: false`.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Spring Security 6, Spring Data JPA / Hibernate (`@EntityGraph`, `JpaSpecificationExecutor`), MySQL 8.0, Spring Data Redis, Spring Kafka (`spring-boot-starter-kafka`), Apache Kafka 3.9.0 KRaft (Docker), JUnit 5, Mockito, MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-25-phase-c-client-apis-design.md`

---

## Global Constraints

- **Java 25 / Surefire:** Preserve `-XX:+EnableDynamicAgentLoading -Dnet.bytebuddy.experimental=true` in `ewallet-backend/pom.xml`.
- **Open-In-View Disabled:** `spring.jpa.open-in-view: false` MUST be enabled in `application.yaml` so SSE streams never hold MySQL connections, and no JPA entity may escape `@Transactional` boundaries (use flat DTOs for events and responses).
- **Double-Entry Zero-Sum Invariant:** Cash-out (`WITHDRAW`) must mutate balances exclusively through `LedgerService.executeDoubleEntry` (`DEBIT` user wallet, `CREDIT` `SYSTEM_WALLET`).
- **System Reserve Account Guard:** `SYSTEM_USER` (`id = 1L` / `"0000000000"`) must never be exposed or treated as a valid transfer/QR/lookup recipient.
- **Column Length Invariant:** `transactions.idempotency_key` is `VARCHAR(64)`. Withdrawal `Transaction.idempotencyKey` MUST be `SHA-256` lowercase hex (fixed 64 chars) over `"WD:" + userId + ":" + rawIdempotencyKey`.
- **Kafka Compatibility (Spring Boot 4):** Use `StringSerializer` / `StringDeserializer` with Jackson `ObjectMapper`, never Spring Kafka `JsonSerializer`. In `ewallet-backend/src/test/resources/application.yaml`, set `spring.kafka.listener.auto-startup: false` so non-Kafka tests do not connect to a live broker.

## Review Focus

1. **Hidden `LazyInitializationException` under `open-in-view: false`:** Disabling OSIV in Task 1 could expose lazy proxy access in existing Phase A/B services/controllers or new `TransactionHistory` / `Notification` mappers — pinned by immediate Task 1 full-suite regression run and Task 2 `@EntityGraph` / Task 7 mapper unit+integration tests.
2. **Lookup Error Code Distinction (`0000000000` vs `id = 1L`):** Phone `"0000000000"` fails the Vietnamese mobile regex (`400 INVALID_PHONE_FORMAT`), whereas a DB record with `id = 1L` matching a valid-format phone hits the DB system-user guard (`404 USER_NOT_FOUND`) — pinned by two distinct tests in Task 3.
3. **Withdrawal `Idempotency-Key` > 64 Chars & Inactive Wallet Race:** Very long client `Idempotency-Key` headers must not cause MySQL `DataTruncation`, and a wallet locked between lookup and `FOR UPDATE` must be rejected — pinned by SHA-256 length test and locked-after-fetch test in Task 4.
4. **Partial Notification Failure on Kafka Duplicate (`P2P_TRANSFER`):** When notification #1 (`TRANSFER_SENT`) hits `uk_notif_idemp`, notification #2 (`TRANSFER_RECEIVED`) must still persist, AND duplicate deliveries must NOT trigger redundant SSE pushes — pinned by Task 6 `NotificationPersistenceServiceTest` and `NotificationConsumerTest`.
5. **SSE Async Dispatch Auth Failure & Connection Leak:** SSE timeout/completion dispatches must return cleanly without 401/403 (`DispatcherType.ASYNC` permitted), and opening >5 emitters for the same `userId` must evict the oldest emitter — pinned by Task 6 `SseEmitterRegistryTest` and Task 7 `NotificationControllerIntegrationTest`.

---

### Task 1: Infrastructure Config, Open-In-View Guard, Schema Additions, Shared DTOs & Exceptions

**Files:**
- Modify: `docker/docker-compose.yml`
- Modify: `ewallet-backend/src/main/resources/application.yaml`
- Modify: `ewallet-backend/src/test/resources/application.yaml`
- Modify: `ewallet-backend/src/main/resources/schema.sql`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/config/SecurityBeansConfig.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/enums/NotificationType.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/Withdrawal.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/Notification.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/PageResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/event/TransactionCompletedEvent.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/InvalidPhoneFormatException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/InvalidQrCodeException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/ExpiredQrCodeException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/RateLimitExceededException.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/exception/GlobalExceptionHandler.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/exception/GlobalExceptionHandlerPhaseCTest.java`

**Interfaces:**
- Consumes: Existing `User`, `Transaction`, `TransactionType`, `ErrorResponse`.
- Produces:
  - `PageResponse<T>(content, page, size, totalElements, totalPages, first, last)` with static `PageResponse.from(Page<T>)`
  - `TransactionCompletedEvent(eventId, transactionId, transactionType, senderUserId, senderPhone, senderName, receiverUserId, receiverPhone, receiverName, amount, description, occurredAt)` (flat immutable/builder DTO)
  - `NotificationType { TOPUP_SUCCESS, TRANSFER_SENT, TRANSFER_RECEIVED, WITHDRAW_SUCCESS }`
  - `Withdrawal` JPA entity mapped to `withdrawals`
  - `Notification` JPA entity mapped to `notifications` (Java field `private boolean read;` mapped to `@Column(name = "is_read", nullable = false)` so derived queries `*ReadFalse` compile cleanly)
  - `GlobalExceptionHandler` mappings: `USER_NOT_FOUND` (404), `INVALID_PHONE_FORMAT` (400), `INVALID_QR_CODE` (400), `EXPIRED_QR_CODE` (400), `RATE_LIMIT_EXCEEDED` (429)

- [ ] **Step 1: Write failing unit tests for new exceptions & `PageResponse`**
  - In `GlobalExceptionHandlerPhaseCTest`, instantiate `GlobalExceptionHandler` and assert status & `errorCode` for:
    - `UserNotFoundException` -> HTTP `404`, `errorCode = "USER_NOT_FOUND"`
    - `InvalidPhoneFormatException` -> HTTP `400`, `errorCode = "INVALID_PHONE_FORMAT"`
    - `InvalidQrCodeException` -> HTTP `400`, `errorCode = "INVALID_QR_CODE"`
    - `ExpiredQrCodeException` -> HTTP `400`, `errorCode = "EXPIRED_QR_CODE"`
    - `RateLimitExceededException` -> HTTP `429`, `errorCode = "RATE_LIMIT_EXCEEDED"`
- [ ] **Step 2: Run test to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=GlobalExceptionHandlerPhaseCTest"`
  - Expected: Compilation failure (exception classes not found).
- [ ] **Step 3: Update `docker-compose.yml`, `application.yaml` (main & test), `schema.sql`, and `SecurityBeansConfig.java`**
  - Add `kafka` service (`apache/kafka:3.9.0`, single-node KRaft mode without Zookeeper) to `docker/docker-compose.yml` exposing port `9092` with `KAFKA_NODE_ID=1`, `KAFKA_PROCESS_ROLES=broker,controller`, `KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093`, `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092`, `KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER`, `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT`, `KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093`, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1`, and `CLUSTER_ID`.
  - In `ewallet-backend/src/main/resources/application.yaml`:
    - Set `spring.jpa.open-in-view: false`.
    - Add `spring.kafka.*` (`bootstrap-servers`, `producer.key-serializer`/`value-serializer: StringSerializer`, `consumer.group-id: vwallet-notification-group`, `auto-offset-reset: earliest`, `StringDeserializer`).
    - Add `vwallet.withdraw.*`, `vwallet.qr.*`, `vwallet.lookup.rate-limit-per-minute: 30`, `vwallet.sse.max-emitters-per-user: 5`, `vwallet.kafka.topics.transaction-completed: "vwallet.transaction.completed"`.
  - In `ewallet-backend/src/test/resources/application.yaml`:
    - Set `spring.jpa.open-in-view: false` and `spring.kafka.listener.auto-startup: false`.
  - In `schema.sql`:
    - Add composite indexes `INDEX idx_tx_sender_created (sender_wallet_id, created_at)` and `INDEX idx_tx_receiver_created (receiver_wallet_id, created_at)` inside `CREATE TABLE IF NOT EXISTS transactions (...)`.
    - Add `CREATE TABLE IF NOT EXISTS withdrawals (...)` and `CREATE TABLE IF NOT EXISTS notifications (...)` per Spec Section 4.1.
  - In `SecurityBeansConfig.java`:
    - Add `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` inside `authorizeHttpRequests(...)`.
- [ ] **Step 4: Implement exceptions, `GlobalExceptionHandler` handlers, `PageResponse`, `TransactionCompletedEvent`, `NotificationType`, `Withdrawal`, and `Notification` entities**
  - Create the 4 exception classes extending `RuntimeException`.
  - Update `GlobalExceptionHandler.handleUserNotFoundException` to emit `"USER_NOT_FOUND"` and add handlers for the 4 new exceptions.
  - Create flat `TransactionCompletedEvent`, `PageResponse<T>`, `NotificationType`, `Withdrawal`, `Notification`.
- [ ] **Step 5: Run Task 1 test AND FULL existing 252-test suite (`open-in-view: false` regression check)**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test`
  - Expected: **253+ tests PASS, 0 failures, 0 `LazyInitializationException`** across all Phase A & B tests!
- [ ] **Step 6: Commit**
  - `git add docker/docker-compose.yml ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "chore(phase-c): add schema, Kafka/OSIV config, async dispatch permit, entities, and exceptions"`

---

### Task 2: Paginated Transaction History API (`GET /api/v1/transactions`)

**Files:**
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/repository/TransactionRepository.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/TransactionHistoryItemResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/TransactionQueryService.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/controller/PaymentController.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/TransactionQueryServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/controller/TransactionHistoryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`PageResponse<T>`, composite indexes), `WalletRepository.findByUserId`.
- Produces:
  - `TransactionQueryService.getHistory(Long userId, int page, int size, TransactionType type, TransactionStatus status, String direction, Instant from, Instant to) -> PageResponse<TransactionHistoryItemResponse>`
  - `GET /api/v1/transactions` endpoint in `PaymentController`

- [ ] **Step 1: Write failing unit & integration tests (`TransactionQueryServiceTest`, `TransactionHistoryIntegrationTest`)**
  - In `TransactionQueryServiceTest` (Mockito):
    - `getHistory_allDirections_computesInOutAndCounterparties()`: verifies `P2P_TRANSFER` sent maps to `direction="OUT"` with receiver counterparty, `TOPUP` maps to `direction="IN"` with `"SYSTEM"` / `"VNPay Top-up"`, `WITHDRAW` maps to `direction="OUT"` with `"BANK"` / `"Rút tiền ngân hàng"`.
    - `getHistory_invalidDateRange_throwsIllegalArgument()` (`from > to`).
    - `getHistory_invalidDirection_throwsIllegalArgument()` (not in `ALL`/`IN`/`OUT`).
    - `getHistory_capsPageSizeAt100()` (`size = 500` clamps or rejects -> verify `Pageable.getPageSize() <= 100`).
  - In `TransactionHistoryIntegrationTest` (`@SpringBootTest` + `MockMvc` + `springSecurity()`):
    - Unauthenticated `GET /api/v1/transactions` -> `401`.
    - Authenticated User A receives only User A's transactions (never User B <-> User C), filters work for `direction=IN`, `direction=OUT`, `type=P2P_TRANSFER`, `status=COMPLETED`, and date range, with zero `LazyInitializationException` under `open-in-view=false`.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=TransactionQueryServiceTest,TransactionHistoryIntegrationTest"`
  - Expected: Compilation failure (`TransactionQueryService` not found).
- [ ] **Step 3: Implement `TransactionRepository` `@EntityGraph` + `JpaSpecificationExecutor`, `TransactionHistoryItemResponse`, `TransactionQueryService`, and `PaymentController` endpoint**
  - Update `TransactionRepository` to extend `JpaSpecificationExecutor<Transaction>` and declare:
    ```java
    @Override
    @EntityGraph(attributePaths = {"senderWallet", "senderWallet.user", "receiverWallet", "receiverWallet.user"})
    Page<Transaction> findAll(Specification<Transaction> spec, Pageable pageable);
    ```
  - Implement `TransactionQueryService` (`@Transactional(readOnly = true)`) building JPA `Specification<Transaction>` cleanly, validating `from`/`to`/`direction`/`size`, sorting by `Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))`.
  - Add `@GetMapping("/transactions")` in `PaymentController` with `@CurrentUser Long userId` and optional request params (`page=0`, `size=20`, `type`, `status`, `direction=ALL`, `from`, `to`).
- [ ] **Step 4: Run tests to verify pass**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=TransactionQueryServiceTest,TransactionHistoryIntegrationTest"`
  - Expected: All pass.
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "feat: add paginated transaction history API with EntityGraph filtering"`

---

### Task 3: Recipient Lookup by Phone (`GET /api/v1/users/lookup`) & System User Guard

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/UserLookupResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/UserLookupService.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/controller/UserController.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/service/TransferService.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/UserLookupServiceTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/service/TransferServiceTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/controller/UserControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`InvalidPhoneFormatException`, `RateLimitExceededException`), `PhoneNumberUtils`, `UserRepository`, `WalletRepository`, `RedisTemplate`.
- Produces:
  - `UserLookupResponse(phoneNumber, fullName, isSelf, canReceive)`
  - `UserLookupService.lookupByPhone(Long currentUserId, String rawPhone) -> UserLookupResponse`
  - `GET /api/v1/users/lookup?phone=...` in `UserController`

- [ ] **Step 1: Write failing tests (`UserLookupServiceTest`, `TransferServiceTest`, `UserControllerIntegrationTest`)**
  - In `UserLookupServiceTest` (Mockito):
    - `lookup_validPhone_returnsActiveRecipient()` (`+84987654321` normalizes to `0987654321`, `isSelf=false`, `canReceive=true`).
    - `lookup_selfPhone_returnsIsSelfTrueAndCanReceiveFalse()`.
    - `lookup_systemPhone0000000000_throwsInvalidPhoneFormat400()`: verifies `"0000000000"` fails regex early with `InvalidPhoneFormatException` (HTTP `400 INVALID_PHONE_FORMAT`), NOT `404`.
    - `lookup_dbSystemUserId1_throwsUserNotFound404()`: mocks `userRepository.findByPhoneNumber("0909999999")` returning `User(id=1L)` -> asserts throws `UserNotFoundException` (HTTP `404 USER_NOT_FOUND`).
    - `lookup_rateLimitExceeded_throws429()` & `lookup_orphanCounterWithoutTtl_setsTtl60s()` (when `count > 1` and `getExpire(key) == -1L`, re-applies `expire(key, 60s)`).
  - In `TransferServiceTest`:
    - `transfer_toSystemUserId1_throwsWalletException()`: attempting P2P transfer to `User(id=1L)` is rejected without debiting balance.
  - In `UserControllerIntegrationTest`:
    - Test `GET /api/v1/users/lookup?phone=+84987654321` -> `200 OK` with `canReceive=true`, `phone=0000000000` -> `400 INVALID_PHONE_FORMAT`, nonexistent `0911999888` -> `404 USER_NOT_FOUND`.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=UserLookupServiceTest,TransferServiceTest,UserControllerIntegrationTest"`
  - Expected: Compilation failure (`UserLookupService` not found).
- [ ] **Step 3: Implement `UserLookupResponse`, `UserLookupService`, `UserController.lookup`, and `TransferService` system-user guard**
  - In `UserLookupService`: normalize/validate phone with `PhoneNumberUtils.normalize` (catch `IllegalArgumentException` -> throw `InvalidPhoneFormatException`); increment `ratelimit:lookup:{currentUserId}` and call `redisTemplate.expire(key, Duration.ofSeconds(60))` whenever `count == 1 || ttl == null || ttl < 0`; lookup user and reject `Long.valueOf(1L).equals(targetUser.getId())` with `UserNotFoundException`; compute `isSelf` & `canReceive`.
  - In `UserController`: add `@GetMapping("/lookup") public ResponseEntity<UserLookupResponse> lookupUser(@CurrentUser Long userId, @RequestParam("phone") String phone)`.
  - In `TransferService.executeTransferTx`: right after loading `receiverUser`, reject if `Long.valueOf(1L).equals(receiverUser.getId()) || Long.valueOf(1L).equals(receiverWallet.getId())` (`throw new WalletException("Không thể chuyển tiền tới tài khoản hệ thống")`).
- [ ] **Step 4: Run tests to verify pass**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=UserLookupServiceTest,TransferServiceTest,UserControllerIntegrationTest"`
  - Expected: All pass.
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "feat: add rate-limited recipient phone lookup and system user guards"`

---

### Task 4: Cash-Out / Withdrawal Flow (`POST /api/v1/withdrawals`)

**Files:**
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/repository/WalletRepository.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/service/IdempotencyService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/repository/WithdrawalRepository.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/WithdrawalRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/WithdrawalResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/WithdrawalService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/WithdrawalController.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/WithdrawalServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/controller/WithdrawalControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`Withdrawal`, `TransactionCompletedEvent`), `UserService.verifyPin`, `LedgerService.executeDoubleEntry`, `IdempotencyService`.
- Produces:
  - `WalletRepository.findIdByUserId(Long userId) -> Optional<Long>`
  - `IdempotencyService.formatWithdrawKey(Long userId, String idempotencyKey) -> String`
  - `WithdrawalService.withdraw(Long userId, String idempotencyKey, WithdrawalRequest request) -> WithdrawalResponse`
  - `POST /api/v1/withdrawals` in `WithdrawalController`

- [ ] **Step 1: Write failing tests (`WithdrawalServiceTest`, `WithdrawalControllerIntegrationTest`)**
  - In `WithdrawalServiceTest` (Mockito):
    - `withdraw_success_executesDoubleEntryMinMaxLockAndHashesIdempotencyKey()`: verifies scalar `walletRepository.findIdByUserId` used (no `entityManager.detach`), locks `1L` then `userWalletId` in ascending order, verifies `Transaction.idempotencyKey` has length exactly `64` even when client header `Idempotency-Key` is 100+ chars, verifies `DEBIT` user / `CREDIT` system wallet, saves `Withdrawal`, caches receipt, and publishes flat `TransactionCompletedEvent` (no JPA entity fields).
    - `withdraw_cachedReceipt_returnsImmediatelyWithoutLocking()`.
    - `withdraw_concurrentLock_throwsIdempotencyConflict409()`.
    - `withdraw_walletLockedAfterLookup_throwsWalletExceptionAndReleasesLock()`.
    - `withdraw_wrongPin_throwsInvalidPinAndReleasesLock()`.
    - `withdraw_insufficientBalance_throwsInsufficientBalanceAndReleasesLock()`.
    - `withdraw_belowMinOrAboveMaxAmount_throwsIllegalArgumentAndReleasesLock()`.
  - In `WithdrawalControllerIntegrationTest` (`@SpringBootTest` + `MockMvc` + `springSecurity()`):
    - Authenticated withdrawal deducts wallet balance, creates 2 ledger entries (`DEBIT` user, `CREDIT` `SYSTEM_WALLET`), creates `withdrawals` row, and idempotent retry with same `Idempotency-Key` returns cached `200 OK` without second deduction.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=WithdrawalServiceTest,WithdrawalControllerIntegrationTest"`
  - Expected: Compilation failure (`WithdrawalService` not found).
- [ ] **Step 3: Implement repository queries, DTOs, `WithdrawalService`, and `WithdrawalController`**
  - Add `@Query("SELECT w.id FROM Wallet w WHERE w.user.id = :userId") Optional<Long> findIdByUserId(@Param("userId") Long userId);` to `WalletRepository`.
  - Add `formatWithdrawKey` to `IdempotencyService`.
  - Create `WithdrawalRepository`, `WithdrawalRequest` (annotations for `bankCode`, `bankAccountNumber`, `accountHolderName`, `amount`, `pin`, `description`), and `WithdrawalResponse`.
  - Implement `WithdrawalService` using outer non-transactional idempotency orchestration + `@Transactional executeWithdrawTx` (`self` proxy call), `DigestUtils.sha256Hex("WD:" + userId + ":" + idempotencyKey)` from `commons-codec` (already in `pom.xml`), scalar `findIdByUserId`, `min -> max` `findByIdForUpdate`, `WalletStatus.ACTIVE` validation **after** `FOR UPDATE`, `LedgerService.executeDoubleEntry`, `WithdrawalRepository.save`, and `ApplicationEventPublisher.publishEvent(TransactionCompletedEvent)`.
  - Implement `WithdrawalController` (`POST /api/v1/withdrawals`).
- [ ] **Step 4: Run tests to verify pass**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=WithdrawalServiceTest,WithdrawalControllerIntegrationTest"`
  - Expected: All pass.
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "feat: add idempotent cash-out withdrawals with double-entry ledger settlement"`

---

### Task 5: QR Code Generation & Parsing (`POST /api/v1/qr/generate`, `POST /api/v1/qr/parse`)

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/QrGenerateRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/QrParseRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/QrGenerateResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/QrParseResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/QrCodeService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/QrController.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/QrCodeServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/controller/QrControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`InvalidQrCodeException`, `ExpiredQrCodeException`, `UserLockedException`), `UserRepository`, `WalletRepository`.
- Produces:
  - `QrCodeService.generateQr(Long userId, QrGenerateRequest request) -> QrGenerateResponse`
  - `QrCodeService.parseQr(Long currentUserId, QrParseRequest request) -> QrParseResponse`
  - `POST /api/v1/qr/generate`, `POST /api/v1/qr/parse` in `QrController`

- [ ] **Step 1: Write failing tests (`QrCodeServiceTest`, `QrControllerIntegrationTest`)**
  - In `QrCodeServiceTest` (Mockito):
    - `generate_staticQr_usesBase64UrlWithoutPaddingAndNullAmountExp()` (verifies `d` has no `'='` padding and `sig` is 64-char hex HMAC-SHA256).
    - `generate_dynamicQr_setsAmountAndExpAndVerifiesViaParse()`.
    - `generate_lockedUserOrInactiveWallet_throws403OrWalletException()`.
    - `generate_dynamicAmountOutOfBoundsOrDescriptionTooLong_throwsIllegalArgument()`.
    - `parse_tamperedPayloadOrInvalidSignature_throwsInvalidQrCode400()`.
    - `parse_expiredDynamicQr_throwsExpiredQrCode400()`.
    - `parse_systemUserPhoneOrId1_throwsUserNotFound404()`.
  - In `QrControllerIntegrationTest` (`@SpringBootTest` + `MockMvc` + `springSecurity()`):
    - End-to-end User A generates `DYNAMIC` QR (`amount=150000`), User B parses it (`200 OK`, `canTransfer=true`, `isSelf=false`), and tampered QR returns `400 INVALID_QR_CODE`.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=QrCodeServiceTest,QrControllerIntegrationTest"`
  - Expected: Compilation failure (`QrCodeService` not found).
- [ ] **Step 3: Implement QR DTOs, `QrCodeService`, and `QrController`**
  - Use `Base64.getUrlEncoder().withoutPadding()` / `Base64.getUrlDecoder()` and `HmacSHA256` + `MessageDigest.isEqual`.
  - Validate user/wallet `ACTIVE` on generate, validate `DYNAMIC` amount `[minAmount, maxAmount]` (`1,000` - `50,000,000`) & `description <= 255`.
  - Reject `SYSTEM_USER` (`"0000000000"` or `id == 1L`) on parse with `UserNotFoundException`.
- [ ] **Step 4: Run tests to verify pass**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=QrCodeServiceTest,QrControllerIntegrationTest"`
  - Expected: All pass.
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "feat: add HMAC-SHA256 signed static/dynamic QR generation and parsing"`

---

### Task 6: Kafka Event Publishing (`AFTER_COMMIT`), Isolated Notification Persistence, SSE Registry & Consumer

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/repository/NotificationRepository.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/NotificationResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/event/TransactionEventKafkaPublisher.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/NotificationPersistenceService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/SseEmitterRegistry.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/kafka/NotificationConsumer.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/service/TransferService.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/service/PaymentService.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/event/TransactionEventKafkaPublisherTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/NotificationPersistenceServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/SseEmitterRegistryTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/kafka/NotificationConsumerTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/service/TransferServiceTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (`Notification`, `NotificationType`, `TransactionCompletedEvent`), Task 4 (`WithdrawalService` already publishes event).
- Produces:
  - `NotificationRepository`:
    - `Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable)`
    - `Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable)`
    - `long countByUserIdAndReadFalse(Long userId)`
    - `boolean existsByUserIdAndTransactionIdAndType(Long userId, Long transactionId, NotificationType type)`
    - `@Modifying @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false") int markAllAsReadByUserId(@Param("userId") Long userId)`
  - `NotificationResponse(id, transactionId, type, title, message, read, createdAt)` + `NotificationResponse.from(Notification)`
  - `NotificationPersistenceService.saveIfAbsent(Long userId, Long txId, NotificationType type, String title, String message) -> Optional<NotificationResponse>` (isolated `REQUIRES_NEW` boundary returning empty on duplicate)
  - `SseEmitterRegistry.register(Long userId) -> SseEmitter` (max 5 emitters per user, evicts oldest) & `sendNotification(Long userId, NotificationResponse payload)`
  - `TransactionEventKafkaPublisher.onTransactionCompleted(TransactionCompletedEvent event)` (`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`)
  - `NotificationConsumer.consume(String payloadJson)`

- [ ] **Step 1: Write failing unit tests (`TransactionEventKafkaPublisherTest`, `NotificationPersistenceServiceTest`, `SseEmitterRegistryTest`, `NotificationConsumerTest`)**
  - `TransactionEventKafkaPublisherTest`: verifies event is serialized via `ObjectMapper` to JSON string and sent to Kafka topic with key `txId.toString()`; verifies exception in `kafkaTemplate.send` is swallowed/logged and never thrown.
  - `NotificationPersistenceServiceTest`: verifies `saveIfAbsent` returns `Optional.of(...)` on new insert and returns `Optional.empty()` when `existsByUserIdAndTransactionIdAndType` is true OR when `DataIntegrityViolationException` occurs (simulating race duplicate).
  - `SseEmitterRegistryTest`: registering 6th emitter for same `userId` keeps active count `<= 5` and completes the oldest emitter; `sendNotification` removes dead/throwing emitters cleanly.
  - `NotificationConsumerTest`:
    - `P2P_TRANSFER` inserts `TRANSFER_SENT` + `TRANSFER_RECEIVED` and pushes SSE for both users ONLY when `saveIfAbsent` returns non-empty.
    - When notification #1 (`TRANSFER_SENT`) returns `Optional.empty()` (duplicate) and notification #2 (`TRANSFER_RECEIVED`) returns `Optional.of(...)`, SSE is pushed ONLY for notification #2 (no duplicate SSE push for #1!).
    - `TOPUP` -> `TOPUP_SUCCESS` to receiver; `WITHDRAW` -> `WITHDRAW_SUCCESS` to sender.
  - Update `TransferServiceTest` and `PaymentServiceTest` (make `@Autowired` / optional `ApplicationEventPublisher` stub-safe) to assert `TransactionCompletedEvent` is published upon `P2P_TRANSFER` and `TOPUP` (`vnp_ResponseCode = "00"`), and NOT published when `processWebhook` fails.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=TransactionEventKafkaPublisherTest,NotificationPersistenceServiceTest,SseEmitterRegistryTest,NotificationConsumerTest,TransferServiceTest,PaymentServiceTest"`
  - Expected: Compilation failure (new classes not found).
- [ ] **Step 3: Implement `NotificationRepository`, `NotificationResponse`, `TransactionEventKafkaPublisher`, `NotificationPersistenceService`, `SseEmitterRegistry`, `NotificationConsumer`, and hook event publishing into `TransferService` & `PaymentService`**
  - Note: in `TransferService` and `PaymentService`, inject `Optional<ApplicationEventPublisher>` or `@Autowired(required = false)` / constructor arg (or update unit test mocks cleanly) so existing constructors don't unexpectedly pass `null` NPE in unit tests if Mockito injects mocks. Checking `(eventPublisher != null)` or mocking `ApplicationEventPublisher` in `TransferServiceTest`/`PaymentServiceTest` keeps Mockito clean!
  - In `NotificationPersistenceService`: annotate `saveIfAbsent(...)` with `@Transactional(propagation = Propagation.REQUIRES_NEW)` and catch `DataIntegrityViolationException` around `saveAndFlush(...)` returning `Optional.empty()`.
- [ ] **Step 4: Run tests to verify pass**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=TransactionEventKafkaPublisherTest,NotificationPersistenceServiceTest,SseEmitterRegistryTest,NotificationConsumerTest,TransferServiceTest,PaymentServiceTest"`
  - Expected: All pass.
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "feat: add Kafka AFTER_COMMIT publisher, isolated notification persistence, and SSE registry"`

---

### Task 7: Notification REST Inbox & SSE Stream (`NotificationController`) + End-to-End Integration Verification

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/NotificationService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/NotificationController.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/NotificationServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/controller/NotificationControllerIntegrationTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/integration/PhaseCE2EIntegrationTest.java`

**Interfaces:**
- Consumes: Task 6 (`NotificationRepository`, `NotificationResponse`, `SseEmitterRegistry`, `NotificationConsumer`), `PageResponse<T>`.
- Produces:
  - `NotificationService`:
    - `getNotifications(Long userId, int page, int size, boolean unreadOnly) -> PageResponse<NotificationResponse>`
    - `getUnreadCount(Long userId) -> long`
    - `markAsRead(Long userId, Long notificationId) -> NotificationResponse`
    - `markAllAsRead(Long userId) -> int`
  - `NotificationController` (`/api/v1/notifications`):
    - `GET /`
    - `GET /unread-count`
    - `PATCH /{id}/read`
    - `PATCH /read-all`
    - `GET /stream` (`MediaType.TEXT_EVENT_STREAM_VALUE`)

- [ ] **Step 1: Write failing unit & integration tests (`NotificationServiceTest`, `NotificationControllerIntegrationTest`, `PhaseCE2EIntegrationTest`)**
  - `NotificationServiceTest` (Mockito):
    - `getNotifications_unreadOnlyTrue_callsUnreadQuery()`.
    - `markAsRead_nonOwner_throws403Forbidden()`.
    - `markAsRead_notFound_throws404NotFound()`.
    - `markAllAsRead_executesBulkUpdateAndReturnsCount()`.
  - `NotificationControllerIntegrationTest` (`@SpringBootTest` + `MockMvc` + `springSecurity()`):
    - `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH /api/v1/notifications/{id}/read` (owner -> `200`, non-owner -> `403`), `PATCH /api/v1/notifications/read-all` -> bulk updates & sets unread count to `0`.
    - `GET /api/v1/notifications/stream` -> returns `200 OK` with `text/event-stream` and initial `connected` event; unauthenticated -> `401`.
  - `PhaseCE2EIntegrationTest` (`@SpringBootTest` + `MockMvc` + `springSecurity()`, `@MockitoBean KafkaTemplate<String, String>` + `@MockitoBean RedisTemplate<String, Object>`):
    - Full flow: Register User A -> Set PIN -> Top-up + Webhook (`00`) -> Lookup User B -> Generate/Parse QR -> P2P Transfer -> Withdraw (`POST /api/v1/withdrawals`) -> Query `GET /api/v1/transactions` -> Invoke `NotificationConsumer` with captured Kafka payload -> Query `GET /api/v1/notifications` & `mark-read` -> Verify all balances & `open-in-view=false` clean.
    - `@AfterEach` cleans up test users/wallets/transactions/withdrawals/notifications (`id > 3`) and Redis store.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test "-Dtest=NotificationServiceTest,NotificationControllerIntegrationTest,PhaseCE2EIntegrationTest"`
  - Expected: Compilation failure (`NotificationService`, `NotificationController` not found).
- [ ] **Step 3: Implement `NotificationService` and `NotificationController`**
  - Ensure `NotificationService` uses `@Transactional(readOnly = true)` for reads and `@Transactional` for `markAsRead` / `markAllAsRead`, converting entities to `NotificationResponse` inside the transaction boundary (`open-in-view=false` safe!).
  - Implement all 5 endpoints in `NotificationController`.
- [ ] **Step 4: Run Task 7 tests and FULL Maven regression test suite**
  - Run: `.\ewallet-backend\mvnw.cmd -f ewallet-backend\pom.xml test`
  - Expected: 100% PASS (0 failures, 0 errors, 0 skipped across Phase A + Phase B + Phase C).
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/ ewallet-backend/src/test/`
  - `git commit -m "feat: add NotificationController REST inbox, SSE stream, and Phase C E2E verification"`
