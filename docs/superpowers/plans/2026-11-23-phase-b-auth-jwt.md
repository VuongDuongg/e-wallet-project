# Phase B Spring Security 6 & JWT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement enterprise-grade authentication and authorization using Spring Security 6 and JWT for the VWallet core backend, supporting user registration with wallet creation, single-device session management with Refresh Token Rotation (RTR) and 30s Grace Period, immediate token blacklisting on logout, transaction PIN lifecycle, and zero-fallback `@CurrentUser` context extraction.

**Architecture:** Stateless JWT architecture where access tokens (15m TTL) carrying claim `type="ACCESS"` and `jti` are authenticated via `JwtAuthenticationFilter` with Redis blacklist checks. Refresh tokens (7d TTL) carrying `type="REFRESH"` are stored in Redis (`auth:refresh:{userId}`) following a Single-Device model and rotated on refresh using a 5s distributed lock (`auth:refresh_lock:{userId}`) and a 30s Grace Period cache (`auth:rotated:{oldTokenId}`). `@CurrentUser Long userId` is resolved strictly from `SecurityContextHolder` with zero fallback to insecure headers.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Spring Security 6, `io.jsonwebtoken:jjwt-api:0.12.6` (plus `jjwt-impl`, `jjwt-jackson`), Spring Data JPA / Hibernate, MySQL 8.0, Spring Data Redis, Lombok 1.18.48, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-11-23-phase-b-auth-jwt-design.md`

## Global Constraints

- Java 25 compatibility: Maven surefire configuration must retain `-XX:+EnableDynamicAgentLoading -Dnet.bytebuddy.experimental=true`.
- Zero balance mutation outside `LedgerService` and immutable ledger entry invariant must be preserved.
- Phone number validation regex must strictly be `^(\\+84|0)[35789][0-9]{8}$` and normalized to 10-digit domestic format (`0...`) at service boundaries.
- Redis keys: `auth:refresh:{userId}` (String, active `tokenId`, TTL 7d), `auth:refresh_lock:{userId}` (String, `"LOCKED"`, TTL 5s), `auth:rotated:{oldTokenId}` (String, JSON payload, TTL 30s), `auth:blacklist:{jti}` (String, `"REVOKED"`, TTL remaining seconds).
- Zero fallback to `X-User-Id` in `CurrentUserArgumentResolver`.
- Public whitelist must include `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/payments/webhook`, `/api/v1/payments/vnpay-return`, Swagger, and Actuator health.
- `JwtAuthenticationFilter.shouldNotFilter()` must bypass public endpoints to prevent expired access tokens from blocking `/refresh`.

## Review Focus

1. **Token Type Confusion:** Client passes a Refresh Token as a `Bearer` token to a protected endpoint (e.g. `/api/v1/transfers`) or an Access Token to `/api/v1/auth/refresh` — must be rejected immediately with HTTP 401 `INVALID_TOKEN_TYPE`.
2. **Concurrent Mobile Refresh Race Condition:** Two requests hit `/api/v1/auth/refresh` simultaneously with the same refresh token — the second request must receive the cached rotated token pair within the 30s Grace Period and must NOT trigger breach lockout.
3. **Breach Reuse Detection:** Client submits a refresh token that is neither the current active token nor inside the 30s Grace Period cache — must revoke `auth:refresh:{userId}` immediately and return HTTP 401 `TOKEN_REUSE_DETECTED`.
4. **Inactive User Lockout on Token Refresh:** User account is marked `LOCKED` or `INACTIVE` in MySQL after initial login — subsequent calls to `/api/v1/auth/refresh` must query the DB, detect inactive status, and return HTTP 403 Forbidden.
5. **IDOR on Transaction Queries:** User C queries `GET /api/v1/transactions/{id}` where the transaction belongs to User A and User B — must return HTTP 403 Forbidden.

---

### Task 1: Dependencies, Properties, Phone Utilities, & JWT Core Infrastructure

**Files:**
- Modify: `ewallet-backend/pom.xml`
- Modify: `ewallet-backend/src/main/resources/application.yaml`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/util/PhoneNumberUtils.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/security/UserPrincipal.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/security/JwtTokenProvider.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/util/PhoneNumberUtilsTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/security/JwtTokenProviderTest.java`

**Interfaces:**
- Consumes: None (builds on existing Spring/Java 25 foundation).
- Produces:
  - `PhoneNumberUtils.normalize(String rawPhone) -> String`
  - `UserPrincipal(Long userId, String phoneNumber, String fullName, String jti, Instant expiresAt)`
  - `JwtTokenProvider.generateAccessToken(Long userId, String phone, String name) -> String` (claim `type="ACCESS"`, TTL 15m)
  - `JwtTokenProvider.generateRefreshToken(Long userId, String tokenId) -> String` (claim `type="REFRESH"`, TTL 7d)
  - `JwtTokenProvider.parseClaims(String token) -> Claims`
  - `JwtTokenProvider.validateToken(String token) -> boolean`
  - `JwtTokenProvider.getTokenType(String token) -> String`

- [ ] **Step 1: Write failing tests for `PhoneNumberUtilsTest` and `JwtTokenProviderTest`**
  - Test normalization: `+84912345678` -> `0912345678`, `0912345678` -> `0912345678`, invalid formats throw `IllegalArgumentException`.
  - Test JWT generation and validation: Verify Access Token claims (`sub=userId`, `phone`, `name`, `type="ACCESS"`, `jti`, `exp`), Refresh Token claims (`sub=userId`, `jti=tokenId`, `type="REFRESH"`), test expired token validation failure, test token type extraction.
- [ ] **Step 2: Run tests to verify failure**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=PhoneNumberUtilsTest,JwtTokenProviderTest`
  - Expected: Compilation failure (classes not found).
- [ ] **Step 3: Implement dependencies, properties, and core classes**
  - Add `io.jsonwebtoken:jjwt-api:0.12.6`, `jjwt-impl:0.12.6`, `jjwt-jackson:0.12.6` to `ewallet-backend/pom.xml`.
  - Add JWT configurations to `application.yaml`:
    ```yaml
    vwallet:
      jwt:
        secret: "dGhpc19pc19hX3Zlcnlfc2VjdXJlXzI1Nl9iaXRfc2VjcmV0X2tleV9mb3JfdndhbGxldF9kZXYh"
        access-token-expiration-seconds: 900
        refresh-token-expiration-seconds: 604800
        refresh-grace-period-seconds: 30
    ```
  - Implement `PhoneNumberUtils` with regex `^(\\+84|0)[35789][0-9]{8}$`.
  - Implement `UserPrincipal` implementing `UserDetails` or custom principal carrying `userId`, `phoneNumber`, `fullName`, `jti`, `expiresAt`.
  - Implement `JwtTokenProvider` using `Jwts.builder()` and `Keys.hmacShaKeyFor()`.
- [ ] **Step 4: Run tests to verify they pass**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=PhoneNumberUtilsTest,JwtTokenProviderTest`
  - Expected: Tests pass (0 failures).
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/pom.xml ewallet-backend/src/main/resources/application.yaml ewallet-backend/src/main/java/com/meme/vwallet/util/ ewallet-backend/src/main/java/com/meme/vwallet/security/ ewallet-backend/src/test/java/com/meme/vwallet/`
  - `git commit -m "feat: add JWT core infrastructure, UserPrincipal, and PhoneNumberUtils"`

---

### Task 2: Domain Exceptions, DTOs, & User PIN Validation Enhancements

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/UserAlreadyExistsException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/PinNotSetException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/PinAlreadySetException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/InvalidTokenTypeException.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/exception/TokenReuseException.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/exception/GlobalExceptionHandler.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/RegisterRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/LoginRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/RefreshTokenRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/LogoutRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/request/PinSetupRequest.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/AuthResponse.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/dto/response/UserMeResponse.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/service/UserService.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/UserServicePinTest.java`

**Interfaces:**
- Consumes: Task 1 (`PhoneNumberUtils`).
- Produces:
  - `RegisterRequest(phoneNumber, fullName, password)` with regex validation `^(\\+84|0)[35789][0-9]{8}$`.
  - `AuthResponse(userId, phoneNumber, fullName, hasPin, accessToken, refreshToken, tokenType, expiresIn)`.
  - `UserService.verifyPin(Long userId, String pin)`: throws `PinNotSetException` if `pinHash` is null/empty; throws `InvalidPinException` if hash does not match.
  - `UserService.setInitialPin(Long userId, String pin)`: checks `user.getPinHash() != null` -> throws `PinAlreadySetException`.
  - `GlobalExceptionHandler` handles `PIN_NOT_SET` (400), `PIN_ALREADY_SET` (400), `INVALID_TOKEN_TYPE` (401), `TOKEN_REUSE_DETECTED` (401), `USER_ALREADY_EXISTS` (409).

- [ ] **Step 1: Write failing test in `UserServicePinTest`**
  - Test verifying PIN when `pin_hash` is null -> assert throws `PinNotSetException`.
  - Test verifying PIN when `pin_hash` is invalid -> assert throws `InvalidPinException`.
  - Test setting PIN when already set -> assert throws `PinAlreadySetException`.
- [ ] **Step 2: Run test to verify failure**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=UserServicePinTest`
  - Expected: Compilation failure (exceptions/methods not found).
- [ ] **Step 3: Implement domain exceptions, DTOs, and UserService PIN methods**
  - Create the 5 exception classes extending `RuntimeException`.
  - Add `@ExceptionHandler` handlers in `GlobalExceptionHandler` returning `ResponseEntity<ErrorResponse>`.
  - Create DTOs with `@NotBlank`, `@Size`, `@Pattern(regexp = "^(\\+84|0)[35789][0-9]{8}$")`.
  - Update `UserService.java`:
    - Add `verifyPin(Long userId, String pin)` checking null/blank first -> `PinNotSetException`.
    - Add `setInitialPin(Long userId, String pin)` checking existing hash -> `PinAlreadySetException`.
- [ ] **Step 4: Run tests to verify they pass**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=UserServicePinTest`
  - Expected: Tests pass (0 failures).
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/java/com/meme/vwallet/exception/ ewallet-backend/src/main/java/com/meme/vwallet/dto/ ewallet-backend/src/main/java/com/meme/vwallet/service/ ewallet-backend/src/test/java/com/meme/vwallet/service/`
  - `git commit -m "feat: add Phase B exceptions, auth DTOs, and PIN lifecycle checks"`

---

### Task 3: Authentication Service & Redis Session Lifecycle Management

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/service/AuthService.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1 & 2 (`PhoneNumberUtils`, `JwtTokenProvider`, `UserPrincipal`, DTOs, Exceptions, `UserRepository`, `WalletRepository`, `RedisTemplate`, `PasswordEncoder`).
- Produces:
  - `AuthService.register(RegisterRequest request) -> AuthResponse`
  - `AuthService.login(LoginRequest request) -> AuthResponse`
  - `AuthService.refreshToken(RefreshTokenRequest request) -> AuthResponse`
  - `AuthService.logout(UserPrincipal principal, String refreshToken) -> void`

- [ ] **Step 1: Write failing unit tests in `AuthServiceTest`**
  - Test `register`: verifies phone normalization, calls `userRepository.save()` and `walletRepository.save()` with `balance=0`, `currency="VND"`, `version=0`, `status=ACTIVE`. Verifies Redis `auth:refresh:{userId}` is stored.
  - Test `login`: canonicalizes phone, checks password, verifies inactive user returns 403, overwrites Redis `auth:refresh:{userId}` with new `tokenId`.
  - Test `refreshToken` - Happy path: validates `type=="REFRESH"`, rotates token, caches old in `auth:rotated:{tokenId}` (30s TTL), updates `auth:refresh:{userId}`.
  - Test `refreshToken` - Grace Period hit: second concurrent call finds `auth:rotated:{tokenId}` and returns cached `AuthResponse`.
  - Test `refreshToken` - Reuse/Breach: unknown/stale token triggers deletion of `auth:refresh:{userId}` and throws `TokenReuseException`.
  - Test `refreshToken` - Lock timeout: 3 retries without finding rotated token throws `409 Conflict`.
  - Test `refreshToken` - User inactive: user status checked in DB; if `LOCKED`/`INACTIVE`, throws `UserLockedException` (403).
  - Test `logout`: writes `auth:blacklist:{jti}` to Redis with remaining TTL, deletes `auth:refresh:{userId}`.
- [ ] **Step 2: Run test to verify failure**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=AuthServiceTest`
  - Expected: Compilation failure (`AuthService` does not exist).
- [ ] **Step 3: Implement `AuthService`**
  - Annotate `register` with `@Transactional`. Create `User` (password hashed) and `Wallet`. Generate tokens and save `auth:refresh:{userId}`.
  - Implement `login`: query user by normalized phone, verify `passwordEncoder.matches()`, verify `user.getStatus() == "ACTIVE"`, set `auth:refresh:{userId}` in Redis (TTL 7d).
  - Implement `refreshToken`:
    - Parse claims, verify `type == "REFRESH"`.
    - Check `auth:rotated:{tokenId}` -> if found, return cached `AuthResponse`.
    - Acquire lock `SET auth:refresh_lock:{userId} "LOCKED" NX EX 5`.
    - If lock fails: retry 3 times with 100ms interval. If `auth:rotated:{tokenId}` appears, return it. If still locked after 300ms, throw `ConflictException` (409).
    - Inside critical section: verify `user.getStatus() == "ACTIVE"` from DB; if not, release lock & throw `UserLockedException`.
    - Verify `auth:refresh:{userId}` == `tokenId`. If not, delete `auth:refresh:{userId}` and throw `TokenReuseException`.
    - Rotate: generate new token pair, update `auth:refresh:{userId}`, save `auth:rotated:{tokenId}` with 30s TTL. Release lock in `finally` if acquired.
  - Implement `logout`: extract `jti` and `expiresAt` from `UserPrincipal`, write `auth:blacklist:{jti}` to Redis, delete `auth:refresh:{userId}`.
- [ ] **Step 4: Run tests to verify they pass**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=AuthServiceTest`
  - Expected: Tests pass (0 failures).
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/java/com/meme/vwallet/service/AuthService.java ewallet-backend/src/test/java/com/meme/vwallet/service/AuthServiceTest.java`
  - `git commit -m "feat: implement AuthService with single-device RTR, Redis lock, and 30s grace period"`

---

### Task 4: Spring Security Filter Chain & Zero-Fallback CurrentUser Resolver

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/security/JwtAuthenticationFilter.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/security/CurrentUserArgumentResolver.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/config/SecurityBeansConfig.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/security/JwtAuthenticationFilterTest.java`
- Test: `ewallet-backend/src/test/java/com/meme/vwallet/security/CurrentUserArgumentResolverTest.java`

**Interfaces:**
- Consumes: Task 1 (`JwtTokenProvider`, `UserPrincipal`), Task 2 (`InvalidTokenTypeException`), RedisTemplate.
- Produces:
  - `JwtAuthenticationFilter`: intercepts `Authorization: Bearer <token>`, verifies `type=="ACCESS"`, checks `auth:blacklist:{jti}`, populates `SecurityContextHolder`. Overrides `shouldNotFilter()` for public routes.
  - `CurrentUserArgumentResolver`: extracts `userId` strictly from `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` (throws 401 Unauthorized if unauthenticated). Zero fallback to `X-User-Id`.
  - `SecurityBeansConfig`: registers `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`, configures public vs authenticated path matchers.

- [ ] **Step 1: Write failing tests in `JwtAuthenticationFilterTest` and `CurrentUserArgumentResolverTest`**
  - Test filter: public URLs (e.g. `/api/v1/auth/refresh`) with expired access token are bypassed (`shouldNotFilter == true`).
  - Test filter: protected URL with Refresh Token (`type="REFRESH"`) returns 401 `INVALID_TOKEN_TYPE`.
  - Test filter: protected URL with blacklisted `jti` returns 401 `TOKEN_REVOKED`.
  - Test filter: protected URL with valid Access Token sets `SecurityContext` with `UserPrincipal` and calls chain.
  - Test resolver: authenticated `UserPrincipal` returns `userId`; unauthenticated or missing context throws `ResponseStatusException(HttpStatus.UNAUTHORIZED)`. Assert header `X-User-Id` is completely ignored.
- [ ] **Step 2: Run test to verify failure**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=JwtAuthenticationFilterTest,CurrentUserArgumentResolverTest`
  - Expected: Compilation or assertion failure.
- [ ] **Step 3: Implement `JwtAuthenticationFilter`, refactor resolver and security config**
  - Implement `JwtAuthenticationFilter` extending `OncePerRequestFilter`:
    - Override `shouldNotFilter(HttpServletRequest request)`: match `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/payments/webhook`, `/api/v1/payments/vnpay-return`, `/swagger-ui/**`, `/v3/api-docs/**`.
    - In `doFilterInternal`: extract token from `Authorization: Bearer `, check `getTokenType(token).equals("ACCESS")`, check `redisTemplate.hasKey("auth:blacklist:" + jti)`. If blacklisted or wrong type, write 401 JSON error and return. Otherwise set `SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userPrincipal, null, authorities))`.
  - In `CurrentUserArgumentResolver.java`: remove all `X-User-Id` fallback logic. Strictly inspect `SecurityContextHolder`.
  - In `SecurityBeansConfig.java`:
    - Add `jwtAuthenticationFilter` parameter to `securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)`.
    - Add `.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`.
    - Whitelist public matchers: `.requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/payments/webhook", "/api/v1/payments/vnpay-return", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()`.
    - Authenticate all other requests: `.anyRequest().authenticated()`.
- [ ] **Step 4: Run tests to verify they pass**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=JwtAuthenticationFilterTest,CurrentUserArgumentResolverTest`
  - Expected: Tests pass (0 failures).
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/java/com/meme/vwallet/security/ ewallet-backend/src/main/java/com/meme/vwallet/config/SecurityBeansConfig.java ewallet-backend/src/test/java/com/meme/vwallet/security/`
  - `git commit -m "feat: configure JwtAuthenticationFilter and zero-fallback CurrentUserArgumentResolver"`

---

### Task 5: Auth & User Controllers, Transaction IDOR Protection

**Files:**
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/AuthController.java`
- Create: `ewallet-backend/src/main/java/com/meme/vwallet/controller/UserController.java`
- Modify: `ewallet-backend/src/main/java/com/meme/vwallet/controller/PaymentController.java`
- Create: `ewallet-backend/src/test/java/com/meme/vwallet/controller/AuthControllerIntegrationTest.java`
- Create: `ewallet-backend/src/test/java/com/meme/vwallet/controller/UserControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1–4 (`AuthService`, `UserService`, `PaymentService`, `@CurrentUser`).
- Produces:
  - `POST /api/v1/auth/register` (Public) -> `201 Created` with `AuthResponse`.
  - `POST /api/v1/auth/login` (Public) -> `200 OK` with `AuthResponse`.
  - `POST /api/v1/auth/refresh` (Public) -> `200 OK` with rotated `AuthResponse`.
  - `POST /api/v1/auth/logout` (Authenticated) -> `200 OK`.
  - `POST /api/v1/users/pin` (Authenticated) -> `200 OK`.
  - `GET /api/v1/users/me` (Authenticated) -> `200 OK` with `UserMeResponse`.
  - `GET /api/v1/payments/transactions/{id}` (Authenticated) -> enforces IDOR check (caller must own sender or receiver wallet; else 403 Forbidden).

- [ ] **Step 1: Write failing integration tests**
  - `AuthControllerIntegrationTest`:
    - Register valid user -> 201 Created, verifies wallet balance 0 and `hasPin=false`.
    - Login with valid credentials -> 200 OK.
    - Refresh token rotation within Grace Period -> 200 OK.
    - Token Type Confusion: calling protected endpoint with Refresh Token -> 401 `INVALID_TOKEN_TYPE`.
    - Logout -> Access Token immediately blacklisted; subsequent request returns 401.
  - `UserControllerIntegrationTest`:
    - Set PIN when `hasPin=false` -> 200 OK.
    - Set PIN again when already set -> 400 `PIN_ALREADY_SET`.
    - Get `/api/v1/users/me` -> 200 OK with correct status and `hasPin`.
    - Transaction IDOR: User A queries transaction of User B & C -> 403 Forbidden.
- [ ] **Step 2: Run test to verify failure**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=AuthControllerIntegrationTest,UserControllerIntegrationTest`
  - Expected: Compilation or 404 failure.
- [ ] **Step 3: Implement `AuthController`, `UserController`, and IDOR check**
  - Implement `AuthController`: `@RestController @RequestMapping("/api/v1/auth")` with endpoints for register, login, refresh, logout (injecting `@AuthenticationPrincipal UserPrincipal`).
  - Implement `UserController`: `@RestController @RequestMapping("/api/v1/users")` with `/pin` calling `userService.setInitialPin(userId, request.getPin())` and `/me` returning `UserMeResponse`.
  - In `PaymentController.java` (and/or transaction query endpoints): inject `@CurrentUser Long userId`, query transaction, assert `userId.equals(senderUserId) || userId.equals(receiverUserId)`. If false, throw `new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập giao dịch này")`.
- [ ] **Step 4: Run tests to verify they pass**
  - Run: `./mvnw test -pl ewallet-backend -Dtest=AuthControllerIntegrationTest,UserControllerIntegrationTest`
  - Expected: Tests pass (0 failures).
- [ ] **Step 5: Commit**
  - `git add ewallet-backend/src/main/java/com/meme/vwallet/controller/ ewallet-backend/src/test/java/com/meme/vwallet/controller/`
  - `git commit -m "feat: implement AuthController, UserController, and IDOR protection"`

---

### Task 6: Phase A Test Suite Migration & Full Regression Verification

**Files:**
- Create: `ewallet-backend/src/test/java/com/meme/vwallet/support/SecurityTestUtils.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/controller/TransferControllerTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/controller/WalletControllerTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/controller/PaymentControllerTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/integration/P2PTransferDeadlockTest.java`
- Modify: `ewallet-backend/src/test/java/com/meme/vwallet/integration/TopupIdempotencyIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`UserPrincipal`, `JwtTokenProvider`), Task 4 (`JwtAuthenticationFilter`, `CurrentUserArgumentResolver`).
- Produces:
  - `SecurityTestUtils.setAuthenticatedUser(Long userId, String phone, String name)`: sets `SecurityContextHolder` with `UserPrincipal`.
  - `SecurityTestUtils.createBearerToken(JwtTokenProvider provider, Long userId)`: generates valid test Bearer token string.

- [ ] **Step 1: Write `SecurityTestUtils` and identify Phase A tests failing due to zero `X-User-Id` fallback**
  - Run existing test suite: `./mvnw test -pl ewallet-backend`
  - Expected: Controllers using `@CurrentUser` fail with 401 Unauthorized because `X-User-Id` is no longer read.
- [ ] **Step 2: Update Phase A test classes to use authenticated SecurityContext**
  - In `TransferControllerTest`, `WalletControllerTest`, `PaymentControllerTest`:
    - Inject `SecurityContext` via `SecurityTestUtils.setAuthenticatedUser(userId)` or provide `Authorization: Bearer <token>` in MockMvc requests.
  - In `P2PTransferDeadlockTest` & `TopupIdempotencyIntegrationTest`:
    - In worker threads calling service/controller directly, ensure `SecurityContextHolder` or user arguments are properly passed.
- [ ] **Step 3: Run full Maven test suite**
  - Run: `./mvnw clean test -pl ewallet-backend`
  - Expected: **All existing Phase A tests + all new Phase B tests pass with BUILD SUCCESS (0 failures, 0 errors, 0 skipped).**
- [ ] **Step 4: Commit**
  - `git add ewallet-backend/src/test/java/`
  - `git commit -m "test: migrate Phase A test suite to SecurityContext authentication and verify zero regressions"`

---

## Plan Self-Review Checklist

1. **Spec coverage:**
   - Registration with automatic wallet creation? -> Task 3 & 5.
   - Login with Access Token (15m) + Refresh Token (7d)? -> Task 1, 3, 5.
   - Single-Device Redis model (`auth:refresh:{userId}`)? -> Task 3.
   - Refresh Token Rotation + 30s Grace Period? -> Task 3 & 5.
   - Token Type Confusion defense (`type="ACCESS"` vs `"REFRESH"`)? -> Task 1, 4, 5.
   - Logout with immediate `jti` blacklist? -> Task 1, 3, 5.
   - Zero-fallback `@CurrentUser`? -> Task 4 & 6.
   - PIN lifecycle (`PIN_NOT_SET` vs `INVALID_PIN` vs `PIN_ALREADY_SET`)? -> Task 2 & 5.
   - Public endpoint whitelist + `shouldNotFilter()` override? -> Task 4.
   - Phone normalization `+84` -> `0` with regex `^(\\+84|0)[35789][0-9]{8}$`? -> Task 1, 2, 3.
   - IDOR prevention on transaction retrieval? -> Task 5.
2. **Step scan:** Each task has distinct Step 1 (failing test), Step 2 (verify fail), Step 3 (implement), Step 4 (verify pass), Step 5 (commit).
3. **Type consistency:** `UserPrincipal` (Task 1) matches `AuthService` (Task 3) and `JwtAuthenticationFilter` (Task 4). `auth:refresh:{userId}` consistently stores active `tokenId`.
4. **Review Focus:** All 5 focus scenarios are mapped to explicit tests in Tasks 1, 3, 4, 5.
5. **Proportion:** Concise, direct, and actionable without bloated code dumps.
