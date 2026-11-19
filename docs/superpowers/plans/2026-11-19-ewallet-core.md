# E-Wallet Core Backend Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the mission-critical core backend engine for an e-wallet platform (`vwallet`) implementing deadlock-free P2P transfers, two-phase payment gateway top-up (VNPay/Mock), distributed Redis idempotency, and an immutable double-entry ledger.

**Architecture:** Monolithic modular Spring Boot application with Spring Data JPA/Hibernate on MySQL 8.0 (InnoDB) and Redis 7. Deterministic row locking (`min/max` wallet ID order) guarantees deadlock-free transfers; double-entry ledger entries back every balance mutation; and Redis `SETNX` prevents duplicate executions.

**Tech Stack:** Java 25, Spring Boot 3.x/4.x, Spring Data JPA / Hibernate, MySQL 8.0, Redis 7, Spring Security (BCrypt), Docker Compose, JUnit 5, Mockito.

**Spec:** [docs/superpowers/specs/2026-11-19-ewallet-core-design.md](file:///d:/Project_Java/e-wallet-project/docs/superpowers/specs/2026-11-19-ewallet-core-design.md)

---

## Global Constraints

- Database & Restart Safety: MySQL 8.0 InnoDB with `CHECK (balance >= 0)`. DDL must use `CREATE TABLE IF NOT EXISTS`, seed data must use `INSERT IGNORE INTO`, and `application.yaml` must set `spring.sql.init.mode: always`.
- Currency: Exclusively Vietnamese Dong (`VND`), represented as `Long` / `BIGINT`.
- Balance Mutation Invariant: Zero balance changes without corresponding immutable `ledger_entries` in the same `@Transactional`.
- Deadlock Prevention: Wallet locks must always be acquired in strictly ascending order: `min(idA, idB)` then `max(idA, idB)`.
- Self-Transfer Prevention: Chặn tự chuyển tiền cho chính mình (`senderWallet.getId().equals(receiverWallet.getId())`) trước khi khóa ví.
- Idempotency Isolation: Key định dạng `idemp:transfer:{senderUserId}:{idempotencyKey}`. Thao tác Redis (lock/cache) đặt ở tầng điều phối bên ngoài ranh giới `@Transactional`.
- Webhook Concurrency & Integrity: Khóa dòng giao dịch bằng `transactionRepository.findByIdForUpdate(id)`, kiểm tra chữ ký (`97`), kiểm tra tồn tại (`01`), kiểm tra idempotency (`00`), và đối soát số tiền khớp 100% (`04`).
- Authentication abstraction: Read user identity via `X-User-Id` header resolved into `@CurrentUser Long userId`.

---

## Review Focus

1. **Simultaneous Bidirectional P2P Transfers:** User A transfers to User B while User B transfers to User A at the exact same millisecond. Must never result in a MySQL deadlock (exercised in Task 8).
2. **Double-Click / Replayed Transfer Requests:** Client sends the same `Idempotency-Key` within 100ms. Must only debit the sender once and return the exact cached receipt (exercised in Task 5 & Task 6).
3. **Overdraft / Insufficient Balance Attempt:** Client attempts to transfer more than wallet balance. Must reject with 400, release/evict Redis lock key immediately, and produce no database changes (exercised in Task 6).
4. **Self-Transfer Attempt:** User transfers to their own phone number. Must reject immediately with `WalletException` before acquiring locks (exercised in Task 6).
5. **Duplicate Webhook Callbacks:** Payment gateway delivers the same successful callback (`vnp_TxnRef`) twice. The second call must not credit the user wallet a second time and return `RspCode: 00` (exercised in Task 7 & Task 8).
6. **Tampered Webhook Amount / Signature:** Attacker alters `vnp_Amount` (returns `RspCode: 04`) or signature (returns `RspCode: 97`) in callback. Must make no state changes (exercised in Task 7).

---

## File Structure

```
d:/Project_Java/e-wallet-project/
├── docker/
│   └── docker-compose.yml                     # MySQL 8.0 + Redis 7 services
├── ewallet-backend/
│   ├── pom.xml                               # Dependencies: JPA, MySQL, Redis, Validation
│   └── src/main/
│       ├── resources/
│       │   ├── application.yaml              # Database, Redis, JPA configuration
│       │   └── schema.sql                    # Initial DDL & seed data (users, wallets, system account)
│       └── java/com/meme/vwallet/
│           ├── VwalletApplication.java
│           ├── config/
│           │   ├── RedisConfig.java          # RedisTemplate and connection setup
│           │   ├── WebMvcConfig.java         # Interceptors / Argument Resolvers
│           │   └── SecurityBeansConfig.java  # BCryptPasswordEncoder
│           ├── security/
│           │   ├── CurrentUser.java          # @CurrentUser annotation
│           │   └── CurrentUserArgumentResolver.java # Resolves X-User-Id header
│           ├── entity/
│           │   ├── enums/                    # TransactionType, TransactionStatus, EntryType, WalletStatus
│           │   ├── User.java
│           │   ├── Wallet.java
│           │   ├── Transaction.java
│           │   └── LedgerEntry.java
│           ├── repository/
│           │   ├── UserRepository.java
│           │   ├── WalletRepository.java     # Pessimistic write lock methods
│           │   ├── TransactionRepository.java
│           │   └── LedgerEntryRepository.java
│           ├── dto/
│           │   ├── request/                  # TransferRequest, TopupRequest, SetPinRequest
│           │   └── response/                 # TransferResponse, TopupResponse, WalletResponse, TransactionResponse
│           ├── exception/
│           │   ├── WalletException.java      # Base custom exception
│           │   ├── InsufficientBalanceException.java
│           │   ├── InvalidPinException.java
│           │   ├── IdempotencyException.java
│           │   └── GlobalExceptionHandler.java # Standardized RFC-7807 error responses
│           └── service/
│               ├── UserService.java          # PIN verification & retrieval
│               ├── LedgerService.java        # Double-entry posting & ledger queries
│               ├── IdempotencyService.java   # Redis SETNX & receipt cache management
│               ├── TransferService.java      # Deadlock-free P2P transfer orchestration
│               ├── VnpayService.java         # HMAC-SHA512 hashing & URL builder
│               └── PaymentService.java       # Gateway top-up & webhook processing
```

---

## Tasks

### Task 1: Environment & Dependency Setup (Docker Compose, POM, Config)

**Files:**
- Create: `docker/docker-compose.yml`
- Modify: `ewallet-backend/pom.xml`
- Modify: `ewallet-backend/src/main/resources/application.yaml`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/VwalletApplicationTests.java`

**Interfaces:**
- Consumes: None (Foundation task)
- Produces: Running MySQL 8 (port 3306) and Redis 7 (port 6379), Spring Boot app bootable with JPA and Redis starters.

- [ ] **Step 1: Create `docker/docker-compose.yml`**
  Write compose file specifying:
  - Service `mysql`: image `mysql:8.0`, port `3306:3306`, DB `vwallet`, user `vwallet_user`, password `vwallet_password`, root password `rootpassword`.
  - Service `redis`: image `redis:7-alpine`, port `6379:6379`.
  - Persistent volumes for `mysql_data` and `redis_data`.

- [ ] **Step 2: Update `ewallet-backend/pom.xml` dependencies & Java 25 configuration**
  - Set `<java.version>25</java.version>`.
  - Cấu hình Lombok phiên bản mới nhất (`1.18.36`) trong `dependencies` và `annotationProcessorPaths`.
  - Cấu hình `maven-surefire-plugin` với cờ JVM:
    ```xml
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
            <argLine>-XX:+EnableDynamicAgentLoading -Dnet.bytebuddy.experimental=true</argLine>
        </configuration>
    </plugin>
    ```
    *(Cờ `-Dnet.bytebuddy.experimental=true` giúp ByteBuddy/Mockito tạo mock class trên Java 25 mà không bị lỗi class file version 69; cờ `-XX:+EnableDynamicAgentLoading` cho phép attach mock agent động).*
  - Thay `org.postgresql:postgresql` thành `com.mysql:mysql-connector-j`.
  - Thay `spring-boot-starter-data-jdbc` thành `spring-boot-starter-data-jpa`.
  - Thêm `spring-boot-starter-data-redis`.
  - Thêm `spring-boot-starter-validation`.
  - Thêm `commons-codec:commons-codec` (cho HMAC-SHA512).

- [ ] **Step 3: Update `src/main/resources/application.yaml`**
  Configure datasource URL `jdbc:mysql://localhost:3306/vwallet?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true`, username `vwallet_user`, password `vwallet_password`, hibernate ddl-auto `none`, redis host `localhost` port `6379`.
  Add database initialization:
  ```yaml
  spring:
    sql:
      init:
        mode: always
  ```

- [ ] **Step 4: Verify application build & context load**
  Run: `mvn clean test-compile` (in `ewallet-backend/`)
  Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
  ```bash
  git add docker/ pom.xml src/main/resources/application.yaml
  git commit -m "chore: setup docker-compose, mysql, jpa, and redis dependencies"
  ```

---

### Task 2: Data Model, JPA Entities & Database Schema Initialization

**Files:**
- Create: `ewallet-backend/src/main/resources/schema.sql`
- Create: `ewallet-backend/src/main/resources/data.sql`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/enums/WalletStatus.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/enums/TransactionType.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/enums/TransactionStatus.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/enums/EntryType.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/User.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/Wallet.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/Transaction.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/entity/LedgerEntry.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/repository/UserRepository.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/repository/WalletRepository.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/repository/TransactionRepository.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/repository/LedgerEntryRepository.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/repository/WalletRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 (JPA dependencies)
- Produces:
  - `WalletRepository.findByIdForUpdate(Long id): Optional<Wallet>` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`
  - `WalletRepository.findByUserId(Long userId): Optional<Wallet>`
  - `UserRepository.findByPhoneNumber(String phone): Optional<User>`
  - `TransactionRepository.findByIdempotencyKey(String key): Optional<Transaction>`
  - `TransactionRepository.findByIdForUpdate(Long id): Optional<Transaction>` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`

- [ ] **Step 1: Write `schema.sql` and `data.sql` with restart-safe statements**
  - Implement full DDL using `CREATE TABLE IF NOT EXISTS` for `users`, `wallets`, `transactions`, `ledger_entries` matching Section 4.2 of Spec. Include `CHECK (balance >= 0)`.
  - In `data.sql`, use `INSERT IGNORE INTO` to seed:
    - User 1: `phone_number = '0000000000'`, `full_name = 'SYSTEM_USER'`, `status = 'ACTIVE'`, `password_hash = '$2a$10$...'`.
    - Wallet 1: `user_id = 1`, `balance = 1000000000000`, `currency = 'VND'`, `status = 'ACTIVE'`.
    - User 2: `phone_number = '0901234567'`, `full_name = 'Nguyen Van A'`, `pin_hash` for "123456".
    - Wallet 2: `user_id = 2`, `balance = 1000000`, `status = 'ACTIVE'`.
    - User 3: `phone_number = '0987654321'`, `full_name = 'Tran Thi B'`, `pin_hash` for "123456".
    - Wallet 3: `user_id = 3`, `balance = 500000`, `status = 'ACTIVE'`.

- [ ] **Step 2: Create JPA Entity classes and Enums**
  - Define `User`, `Wallet`, `Transaction`, `LedgerEntry` matching the schema and relations.
  - In `Wallet.java`, map `version` as `@Version` for optimistic locking fallback, balance as `Long`, status as `WalletStatus`.

- [ ] **Step 3: Create Spring Data JPA Repositories with pessimistic locks**
  - Implement `WalletRepository`:
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
    ```
  - Implement `TransactionRepository`:
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);
    ```

- [ ] **Step 4: Write unit/slice test for `WalletRepositoryTest`**
  - Test `findByIdForUpdate` and negative balance constraint behavior.
  - Run: `mvn test -Dtest=WalletRepositoryTest`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add src/main/resources/schema.sql src/main/resources/data.sql src/main/java/com/meme/vwallet/entity/ src/main/java/com/meme/vwallet/repository/ src/test/
  git commit -m "feat: implement database schema, jpa entities and repositories with pessimistic locking"
  ```

---

### Task 3: Security Config, User Context & PIN Verification Service

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/config/SecurityBeansConfig.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/security/CurrentUser.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/security/CurrentUserArgumentResolver.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/config/WebMvcConfig.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/InvalidPinException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/UserNotFoundException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/UserService.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/UserServiceTest.java`

**Interfaces:**
- Consumes: Task 2 (`UserRepository`, `User`)
- Produces:
  - `@CurrentUser Long userId` controller method parameter resolution via `X-User-Id` header.
  - `UserService.verifyPin(Long userId, String rawPin): void` (throws `InvalidPinException` if mismatched).
  - `UserService.setPin(Long userId, String newPin): void`.

- [ ] **Step 1: Write failing test in `UserServiceTest`**
  - Verify PIN verification throws `InvalidPinException` for incorrect PIN and passes for correct BCrypt PIN.
  - Run: `mvn test -Dtest=UserServiceTest`
  - Expected: FAIL (class/methods not yet implemented).

- [ ] **Step 2: Implement `SecurityBeansConfig` and `UserService`**
  - Register `PasswordEncoder` (`BCryptPasswordEncoder`).
  - Implement `verifyPin` using `passwordEncoder.matches(rawPin, user.getPinHash())`.
  - Implement `setPin` to hash raw 6-digit PIN with BCrypt.

- [ ] **Step 3: Implement `CurrentUserArgumentResolver` & `WebMvcConfig`**
  - Read header `X-User-Id`. If missing or invalid long, throw `UnauthorizedException` (or 401).

- [ ] **Step 4: Run test to verify it passes**
  - Run: `mvn test -Dtest=UserServiceTest`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add src/main/java/com/meme/vwallet/config/ src/main/java/com/meme/vwallet/security/ src/main/java/com/meme/vwallet/service/UserService.java src/test/
  git commit -m "feat: implement user context argument resolver and pin verification service"
  ```

---

### Task 4: Double-Entry Ledger Engine & Repository Service

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/LedgerService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/InsufficientBalanceException.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/LedgerServiceTest.java`

**Interfaces:**
- Consumes: Task 2 (`LedgerEntryRepository`, `WalletRepository`, `LedgerEntry`, `Wallet`)
- Produces:
  - `LedgerService.recordEntry(Transaction tx, Wallet wallet, EntryType type, Long amount): LedgerEntry`
  - `LedgerService.executeDoubleEntry(Transaction tx, Wallet sender, Wallet receiver, Long amount): void`
  - Validates that `sender.balance >= amount` before mutating.

- [ ] **Step 1: Write failing test in `LedgerServiceTest`**
  - Test that `executeDoubleEntry` creates exactly 2 `LedgerEntry` records (1 DEBIT with balance_after reduced, 1 CREDIT with balance_after increased).
  - Test that attempting to debit when `balance < amount` throws `InsufficientBalanceException`.
  - Run: `mvn test -Dtest=LedgerServiceTest`
  - Expected: FAIL.

- [ ] **Step 2: Implement `LedgerService`**
  - In `executeDoubleEntry`:
    ```java
    if (sender.getBalance() < amount) {
        throw new InsufficientBalanceException("Số dư không đủ để thực hiện giao dịch");
    }
    sender.setBalance(sender.getBalance() - amount);
    receiver.setBalance(receiver.getBalance() + amount);
    recordEntry(tx, sender, EntryType.DEBIT, amount, sender.getBalance());
    recordEntry(tx, receiver, EntryType.CREDIT, amount, receiver.getBalance());
    ```

- [ ] **Step 3: Run test to verify it passes**
  - Run: `mvn test -Dtest=LedgerServiceTest`
  - Expected: PASS.

- [ ] **Step 4: Commit**
  ```bash
  git add src/main/java/com/meme/vwallet/service/LedgerService.java src/test/
  git commit -m "feat: implement double-entry ledger engine and balance validation"
  ```

---

### Task 5: Redis Distributed Idempotency Service

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/config/RedisConfig.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/IdempotencyService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/IdempotencyConflictException.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/IdempotencyServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (Redis Starter)
- Produces:
  - Key formatting: `formatTransferKey(Long senderUserId, String idempotencyKey) -> "idemp:transfer:" + senderUserId + ":" + idempotencyKey`
  - `IdempotencyService.acquireLock(String key): boolean` (calls `SETNX` with 120s TTL).
  - `IdempotencyService.releaseLock(String key): void` (evicts `PROCESSING` key on failures).
  - `IdempotencyService.saveReceipt(String key, Object receiptPayload, Duration ttl): void`.
  - `IdempotencyService.getReceipt(String key, Class<T> clazz): Optional<T>`.

- [ ] **Step 1: Write failing test in `IdempotencyServiceTest`**
  - Verify key formatting includes `senderUserId`.
  - Verify first `acquireLock` returns `true`.
  - Verify second concurrent `acquireLock` returns `false` or throws `IdempotencyConflictException`.
  - Verify `releaseLock` enables re-acquisition.
  - Verify `saveReceipt` / `getReceipt` serializes and retrieves DTO correctly.
  - Run: `mvn test -Dtest=IdempotencyServiceTest`
  - Expected: FAIL.

- [ ] **Step 2: Implement `RedisConfig` & `IdempotencyService`**
  - Configure `RedisTemplate<String, Object>` with `StringRedisSerializer` and `GenericJackson2JsonRedisSerializer`.
  - Implement lock acquisition using `redisTemplate.opsForValue().setIfAbsent(key, "PROCESSING", Duration.ofSeconds(120))`.
  - Implement receipt storage: `redisTemplate.opsForValue().set(key, receiptJson, Duration.ofHours(24))`.

- [ ] **Step 3: Run test to verify it passes**
  - Run: `mvn test -Dtest=IdempotencyServiceTest`
  - Expected: PASS.

- [ ] **Step 4: Commit**
  ```bash
  git add src/main/java/com/meme/vwallet/config/RedisConfig.java src/main/java/com/meme/vwallet/service/IdempotencyService.java src/test/
  git commit -m "feat: implement redis idempotency service with namespaced keys and receipt caching"
  ```

---

### Task 6: P2P Money Transfer Service & Controller (Deadlock-Free Concurrency & Transaction Boundary Separation)

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/TransferRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/TransferResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/TransferService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/TransferController.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/GlobalExceptionHandler.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/TransferServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/controller/TransferControllerTest.java`

**Interfaces:**
- Consumes: Task 2 (`WalletRepository`, `TransactionRepository`), Task 3 (`UserService`), Task 4 (`LedgerService`), Task 5 (`IdempotencyService`)
- Produces:
  - `POST /api/v1/transfers` endpoint.
  - `TransferService.transfer(Long senderUserId, String idempotencyKey, TransferRequest request): TransferResponse` (outer non-transactional orchestration).
  - `TransferService.executeTransferTx(Long senderUserId, TransferRequest request): TransferResponse` (inner `@Transactional` boundary).
  - Self-transfer prevention: `if (senderWallet.getId().equals(receiverWallet.getId())) throw new WalletException(...)`.
  - Deterministic locking:
    ```java
    Long firstId = Math.min(senderWallet.getId(), receiverWallet.getId());
    Long secondId = Math.max(senderWallet.getId(), receiverWallet.getId());
    walletRepository.findByIdForUpdate(firstId);
    walletRepository.findByIdForUpdate(secondId);
    ```

- [ ] **Step 1: Write unit tests in `TransferServiceTest`**
  - Test 1: Successful transfer debits sender, credits receiver, creates 2 ledger entries, records completed transaction, caches response in Redis.
  - Test 2: Self-transfer attempt (`senderWallet.getId().equals(receiverWallet.getId())`) throws `WalletException` before lock acquisition.
  - Test 3: Invalid PIN throws `InvalidPinException` and releases idempotency lock.
  - Test 4: Insufficient balance throws `InsufficientBalanceException` and releases idempotency lock.
  - Test 5: Existing `COMPLETED` idempotency key directly returns cached receipt without hitting DB.
  - Run: `mvn test -Dtest=TransferServiceTest`
  - Expected: FAIL.

- [ ] **Step 2: Implement `TransferService` & `TransferController`**
  - Separate outer orchestration from inner transactional execution:
    1. Outer `transfer(...)`: check `getReceipt(key)`, acquire `acquireLock(key)`.
    2. Try `executeTransferTx(...)` (`@Transactional`).
    3. Post-commit: `saveReceipt(key, response, Duration.ofHours(24))`.
    4. Catch `Exception`: `releaseLock(key)` and rethrow.
  - In `executeTransferTx`:
    1. Validate self-transfer.
    2. Verify PIN.
    3. Deterministic `min/max` lock acquisition.
    4. Validate balance & update wallets + insert transaction + insert 2 ledger entries.

- [ ] **Step 3: Implement `GlobalExceptionHandler`**
  - Map `WalletException` & `InvalidPinException` $\rightarrow$ 400 Bad Request.
  - Map `InsufficientBalanceException` $\rightarrow$ 400 Bad Request.
  - Map `IdempotencyConflictException` $\rightarrow$ 409 Conflict.
  - Standardize error JSON format: `{"timestamp": "...", "status": 400, "error": "...", "message": "..."}`.

- [ ] **Step 4: Run controller & service tests**
  - Run: `mvn test -Dtest=TransferServiceTest,TransferControllerTest`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add src/main/java/com/meme/vwallet/dto/ src/main/java/com/meme/vwallet/service/TransferService.java src/main/java/com/meme/vwallet/controller/TransferController.java src/main/java/com/meme/vwallet/exception/GlobalExceptionHandler.java src/test/
  git commit -m "feat: implement p2p transfer service with self-transfer check and separated transaction boundaries"
  ```

---

### Task 7: Payment Gateway (VNPay/Mock) Top-up Service & Webhooks

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/TopupRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/TopupResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/VnpayIpnResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/VnpayService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/PaymentService.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/PaymentController.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/WalletController.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/VnpayServiceTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: Task 2 (`TransactionRepository`, `WalletRepository`), Task 4 (`LedgerService`)
- Produces:
  - `POST /api/v1/payments/topup` (creates `PENDING` transaction, returns `paymentUrl`).
  - `POST /api/v1/payments/webhook` (verifies HMAC-SHA512, debits `SYSTEM_WALLET`, credits user wallet, writes ledger, returns `RspCode: 00`).
  - `GET /api/v1/payments/vnpay-return` (user return redirect handling).
  - `GET /api/v1/transactions/{id}` (query status).
  - `GET /api/v1/wallets/me` (query balance).

- [ ] **Step 1: Write failing test in `VnpayServiceTest`**
  - Verify HMAC-SHA512 checksum creation and verification against VNPay test vectors.
  - Verify URL query string generation with sorted parameters.
  - Run: `mvn test -Dtest=VnpayServiceTest`
  - Expected: FAIL.

- [ ] **Step 2: Implement `VnpayService`**
  - Implement HMAC-SHA512 hashing using `Mac.getInstance("HmacSHA512")`.
  - Implement `createPaymentUrl(...)` and `verifySignature(Map<String, String> params, String secretKey): boolean`.

- [ ] **Step 3: Write tests in `PaymentServiceTest`**
  - Test 1: Phase 1 creates `PENDING` top-up transaction with `SYSTEM_WALLET` as sender.
  - Test 2: Phase 2 Webhook with invalid signature returns `RspCode: 97`.
  - Test 3: Phase 2 Webhook with unknown `vnp_TxnRef` returns `RspCode: 01`.
  - Test 4: Phase 2 Webhook with mismatched amount (`vnp_Amount / 100 != tx.amount`) marks transaction `FAILED` and returns `RspCode: 04`.
  - Test 5: Replayed Webhook on already completed transaction returns `RspCode: 00` without modifying balance or creating duplicate ledger entries.
  - Test 6: Valid Webhook with `ResponseCode=00` transitions transaction to `COMPLETED`, locks wallets, debits System Wallet, credits User Wallet, logs ledger entries, and returns `RspCode: 00`.
  - Run: `mvn test -Dtest=PaymentServiceTest`
  - Expected: FAIL.

- [ ] **Step 4: Implement `PaymentService`, `PaymentController`, and `WalletController`**
  - Implement 5 sequential checks in `processWebhook(...)`:
    1. Signature check via `vnpayService.verifySignature` (`97` on failure).
    2. Lock transaction: `transactionRepository.findByIdForUpdate(txId)` (`01` if not found).
    3. Idempotent status check: if `COMPLETED` return `00` immediately.
    4. Amount check: `Long.parseLong(params.get("vnp_Amount")) / 100 == tx.getAmount()` (`04` on mismatch).
    5. Lock `SYSTEM_WALLET` & `userWallet` (`min/max` order), mutate balances, insert 2 `LedgerEntry` records, update status `COMPLETED`, commit, return `00`.
  - Add query endpoints `GET /api/v1/payments/vnpay-return`, `GET /api/v1/transactions/{id}`, and `GET /api/v1/wallets/me`.

- [ ] **Step 5: Run tests to verify they pass**
  - Run: `mvn test -Dtest=VnpayServiceTest,PaymentServiceTest`
  - Expected: PASS.

- [ ] **Step 6: Commit**
  ```bash
  git add src/main/java/com/meme/vwallet/service/VnpayService.java src/main/java/com/meme/vwallet/service/PaymentService.java src/main/java/com/meme/vwallet/controller/PaymentController.java src/main/java/com/meme/vwallet/controller/WalletController.java src/test/
  git commit -m "feat: implement vnpay payment gateway integration, topup initiation and webhook callback"
  ```

---

### Task 8: End-to-End Integration, Concurrency & Deadlock-Freedom Verification

**Files:**
- Create: `ewallet-backend/src/test/java/com/meme/vwallet/integration/P2PTransferDeadlockTest.java`
- Create: `ewallet-backend/src/test/java/com/meme/vwallet/integration/TopupIdempotencyIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1-7 (Full system integration)
- Produces: Stress & concurrency verification proving the Review Focus guarantees.

- [ ] **Step 1: Write `P2PTransferDeadlockTest`**
  - Spawn 20 concurrent threads using `ExecutorService` and `CountDownLatch`:
    - 10 threads execute User A $\rightarrow$ User B (amount: 1,000 VND each).
    - 10 threads execute User B $\rightarrow$ User A (amount: 1,000 VND each).
  - Verify: Zero `DeadlockLoserDataAccessException` or deadlock errors.
  - Verify: Total ledger balance invariant ($Balance_A + Balance_B$) remains strictly constant.

- [ ] **Step 2: Write `TopupIdempotencyIntegrationTest`**
  - Create a top-up transaction for 500,000 VND.
  - Simultaneously fire 5 concurrent Webhook requests with identical `vnp_TxnRef`.
  - Verify: Exactly 1 webhook performs the credit; 4 others receive idempotent OK.
  - Verify: User wallet balance increases by exactly 500,000 VND (not 2,500,000 VND).

- [ ] **Step 3: Run all integration tests**
  - Run: `mvn test -Dtest=P2PTransferDeadlockTest,TopupIdempotencyIntegrationTest`
  - Expected: PASS.

- [ ] **Step 4: Commit**
  ```bash
  git add src/test/java/com/meme/vwallet/integration/
  git commit -m "test: add concurrent deadlock-free transfer and topup idempotency integration tests"
  ```

---

## Plan Self-Review Checklist

- [x] **Spec coverage:** Every requirement from `2026-11-19-ewallet-core-design.md` has a concrete task (ERD, DDL, P2P lock ordering, double-entry ledger, Redis idempotency, VNPay HMAC URLs & Webhooks, status query APIs).
- [x] **Step scan:** Every step specifies exact files, class names, method signatures, test assertions, and terminal commands.
- [x] **Type consistency:** Entity attributes (`id`, `balance`, `currency`, `amount`), Enums (`TransactionType`, `TransactionStatus`, `EntryType`), and DTO contracts match across tasks.
- [x] **Review Focus:** All 5 high-risk scenarios (deadlock, duplicate transfer, overdraft, duplicate webhook, tampered webhook signature) have corresponding tests in Tasks 6, 7, and 8.
- [x] **Proportion:** Clear, actionable steps without bloating into a source code transcript.
