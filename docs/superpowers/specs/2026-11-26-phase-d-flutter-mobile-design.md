# Phase D Design Specification: Flutter Mobile Client (`ewallet-mobile`)

- **Project:** VWallet (Core E-Wallet Engine)
- **Module:** `ewallet-mobile`
- **Target Platform:** Flutter `3.47.5` / Dart `3.13.4` (Android real physical device primary, iOS-compatible)
- **Author:** Antigravity AI & VuongDuongg
- **Date:** 2026-11-26
- **Status:** Draft (Updated per Technical Review — Awaiting Approval)

---

## 1. Intent & Context

**Phases A, B, and C** completed the `ewallet-backend` service in full:
- **Phase A:** Double-Entry Ledger, deadlock-free P2P transfers, Redis distributed `Idempotency-Key`, and 2-Phase VNPay top-up gateway.
- **Phase B:** Stateless JWT (`ACCESS` 15m / `REFRESH` 7d), Single-Device Refresh Token Rotation (RTR) with 30s grace period & reuse detection (`TOKEN_REUSE_DETECTED`), Redis token blacklist (`TOKEN_REVOKED`), 6-digit transaction PIN (`BCrypt`), and OpenAPI/CORS.
- **Phase C:** Paginated Transaction History, rate-limited Recipient Phone Lookup, atomic Bank Withdrawal (Cash-out), HMAC-SHA256 Signed QR Codes (`STATIC` / `DYNAMIC`), and Apache Kafka + DB Inbox + SSE real-time notifications.

**Phase D** builds the complete end-user mobile client inside `ewallet-mobile/` consuming every Phase A–C endpoint. The design specifically prioritizes real-device reliability around four hard engineering problems:
1. Preventing concurrent `401` refresh race conditions & interceptor deadlocks against the backend's Single-Device RTR + 30s grace period.
2. Preserving `Idempotency-Key` stability across silent token refreshes while regenerating keys cleanly on edited or rejected submissions.
3. Handling VNPay Sandbox WebView redirects on a physical phone where `vnp_ReturnUrl` defaults to `http://localhost:8080/api/v1/payments/vnpay-return`.
4. Keeping SSE real-time notifications responsive without leaking connections against the backend's `max 5 emitters / user` cap.

---

## 2. Goals, Non-Goals, & Anti-Goals

### 2.1. Goals
1. **Complete E-Wallet Mobile Experience:** Auth (Register/Login/Logout), Home Wallet Dashboard, Initial 6-Digit PIN Setup & Reusable PIN Entry Modal, VNPay Top-Up WebView, P2P Transfer with Phone Lookup, Bank Cash-Out Withdrawal, QR Code Generator/Camera Scanner + Manual Paste, Paginated Transaction History with Filters, and Notification Inbox + Real-Time SSE Updates.
2. **Deadlock-Free Single-Flight Token Refresh:** Four isolated Dio instances — `apiDio` (`QueuedInterceptor`), `refreshDio` (refresh calls only), `retryDio` (post-refresh retry without `AuthInterceptor`), and `sseDio` (no `receiveTimeout`) — sharing one `Completer`-based `TokenRefreshCoordinator`.
3. **Physical Device Dev Ergonomics:** Runtime `API_BASE_URL` via `--dart-define`, `adb reverse tcp:8080 tcp:8080` default + LAN IP support, debug-only `usesCleartextTraffic`, and VNPay return interception.
4. **Automated Test Coverage:** Unit tests for `TokenRefreshCoordinator`, `AuthInterceptor`, `IdempotencyKey` lifecycle, SSE parser/backoff, plus Widget tests for the 6-digit PIN keypad, QR manual-paste/scan routing, and VNPay URL interception.

### 2.2. Non-Goals
- Biometric authentication (Fingerprint / FaceID) replacing the 6-digit PIN — `ewallet-backend` validates 6-digit PIN via `BCrypt` on every transfer/withdrawal.
- Firebase Cloud Messaging (FCM) / APNs background push notifications — Phase D uses active-session SSE (`/api/v1/notifications/stream`) + DB Inbox (`/api/v1/notifications`).
- Offline mutation queuing or local SQLite transaction caching.
- Modifying `ewallet-backend` business APIs — `ewallet-mobile` consumes existing Phase A–C contracts as-is.

### 2.3. Anti-Goals (Client Invariants)
- **NO Self-Deadlocking Refresh or Retry Calls:** Both `refreshDio` and `retryDio` must NEVER attach `AuthInterceptor` (`QueuedInterceptor`), preventing both `/refresh` calls and retried requests (even if a retry returns 400/500) from deadlocking inside `apiDio`'s `onError` queue.
- **NO Infinite 401 Retry Loops:** Every retried request is tagged with `extra['_retried'] = true`; a second 401 never triggers another refresh cycle.
- **NO Parallel `/refresh` Races:** Concurrent 401s must wait on a single `Completer` and re-check `FlutterSecureStorage` before calling `/api/v1/auth/refresh`.
- **NO Cleartext HTTP in Release Builds:** `usesCleartextTraffic="true"` is strictly confined to `android/app/src/debug/AndroidManifest.xml`.
- **NO Duplicate Financial Keys on Silent Retry:** A `401` retry or network timeout retry must reuse the same `UUID v4` `Idempotency-Key` for a given user confirmation.
- **NO Unbounded Background SSE Sockets:** Closing/pausing the app (`AppLifecycleState.paused`) must immediately close the active SSE stream.

---

## 3. Architectural Approaches Considered

| Option | Stack | Trade-offs | Decision |
|--------|-------|------------|----------|
| **Approach 1 (Selected)** | Feature-First + `flutter_riverpod` + `go_router` + `dio` (`QueuedInterceptor`) | Clean separation of UI, async state (`AsyncNotifier`), and HTTP/SSE plumbing; minimal boilerplate; straightforward unit/widget testing for interceptor single-flight locks and lifecycle observers. | **Selected** |
| **Approach 2** | Clean Architecture + `flutter_bloc` + `get_it` + `go_router` + `dio` | Enterprise-grade explicit event/state streams, but excessive Event/State/UseCase boilerplate for a ~12-screen app without multi-team scale requirements. | Rejected |
| **Approach 3** | `provider` (`ChangeNotifier`) + `Navigator` | Fastest initial prototype, but brittle when coordinating SSE lifecycle, router auth redirects, single-flight JWT refresh, and PIN modal flows. | Rejected |

---

## 4. Tech Stack & Project Structure (`ewallet-mobile/`)

### 4.1. Core Packages (`pubspec.yaml`)
- **State & Routing:** `flutter_riverpod`, `go_router`
- **Networking & Realtime:** `dio` (REST + `QueuedInterceptor` + `ResponseType.stream` SSE client — avoids brittle 3rd-party SSE plugins)
- **Security & Storage:** `flutter_secure_storage` (EncryptedSharedPreferences / Keychain)
- **QR & WebView:** `mobile_scanner`, `qr_flutter`, `webview_flutter`
- **Utilities:** `uuid`, `intl` (VND currency & date formatting)

### 4.2. Directory Layout
```text
ewallet-mobile/
├── android/
│   └── app/src/
│       ├── main/AndroidManifest.xml      # INTERNET + CAMERA, allowBackup="false" (NO cleartext HTTP)
│       └── debug/AndroidManifest.xml     # android:usesCleartextTraffic="true" (dev only)
├── lib/
│   ├── main.dart
│   ├── app.dart                          # MaterialApp.router + AppLifecycleObserver + SSE binder
│   ├── core/
│   │   ├── config/app_config.dart        # API_BASE_URL, timeouts, VNPay/QR constants
│   │   ├── storage/token_storage.dart    # Secure storage for tokens + user summary
│   │   ├── network/
│   │   │   ├── dio_providers.dart        # apiDio, refreshDio, retryDio, sseDio providers
│   │   │   ├── token_refresh_coordinator.dart
│   │   │   ├── auth_interceptor.dart     # QueuedInterceptor using TokenRefreshCoordinator + retryDio
│   │   │   └── api_exception.dart        # Maps ErrorResponse (status, code, errorCode, message)
│   │   ├── realtime/
│   │   │   ├── sse_parser.dart           # Chunk-safe UTF-8 StreamTransformer + LineSplitter parser
│   │   │   └── sse_notification_service.dart
│   │   ├── routing/app_router.dart       # GoRouter + AuthSessionState (initializing/auth/unauth) guard
│   │   ├── theme/app_theme.dart          # Material 3 VWallet light theme + typography
│   │   └── widgets/
│   │       ├── pin_modal_sheet.dart      # Reusable 6-digit PIN bottom sheet + keypad
│   │       ├── currency_formatter.dart   # VND formatting ("1.000.000 ₫")
│   │       └── status_badge.dart
│   └── features/
│       ├── auth/                         # AuthRepository, AuthSessionNotifier, SplashScreen, LoginScreen, RegisterScreen
│       ├── pin/                          # PinSetupScreen (/api/v1/users/pin)
│       ├── home_wallet/                  # WalletRepository, HomeScreen (balance + quick actions + unread badge)
│       ├── topup/                        # TopupRepository, TopupAmountScreen, VnpayWebViewScreen, TopupResultScreen
│       ├── transfer/                     # TransferRepository, TransferNotifier, TransferScreen, TransferReceiptScreen
│       ├── withdraw/                     # WithdrawRepository, WithdrawNotifier, WithdrawScreen, WithdrawReceiptScreen
│       ├── qr/                           # QrRepository, QrHubScreen (My QR STATIC/DYNAMIC + Scan/Paste tab)
│       ├── history/                      # HistoryRepository, HistoryNotifier, HistoryScreen + FilterSheet + DetailSheet
│       └── notifications/                # NotificationRepository, NotificationsNotifier, NotificationsScreen
└── test/                                 # Unit & Widget tests mirroring core/ and features/
```

---

## 5. Network Architecture & Single-Flight Refresh Interceptor

### 5.1. Four Isolated `Dio` Instances
| Instance | Interceptors | Timeouts | Responsibility |
|----------|--------------|----------|----------------|
| `apiDio` | `AuthInterceptor` (`QueuedInterceptor`) | `connect: 10s`, `receive: 15s`, `send: 15s` | All primary authenticated & standard REST API calls. |
| `refreshDio` | **None** (logging only in debug) | `connect: 10s`, `receive: 10s` | Calls `POST /api/v1/auth/refresh` exclusively so it can never deadlock inside `apiDio`'s `QueuedInterceptor`. |
| `retryDio` | **None** (same `baseUrl` & timeouts as `apiDio`) | `connect: 10s`, `receive: 15s`, `send: 15s` | Executes the single retried request inside `AuthInterceptor.onError()` after token refresh, ensuring retry `400`/`500` responses never re-enter `apiDio`'s `QueuedInterceptor.onError()` and cause self-deadlock. |
| `sseDio` | **None** (manual Bearer header + `TokenRefreshCoordinator`) | `connect: 10s`, `receive: null` (`Duration.zero`) | Long-lived `GET /api/v1/notifications/stream` (`ResponseType.stream`). |

### 5.2. `TokenRefreshCoordinator` (Shared Single-Flight Lock)
Both `AuthInterceptor` (REST) and `SseNotificationService` (SSE) delegate refresh to a single `TokenRefreshCoordinator` singleton:

```dart
// Conceptual contract
Future<RefreshOutcome> refreshIfNeeded({required String? failedAccessToken});
```

1. **Active Refresh Coalescing:** If `_activeCompleter != null`, wait for `_activeCompleter!.future` and return its `RefreshOutcome` without issuing a second HTTP call.
2. **Stale-Token Check Before Calling `/refresh`:** Read current `accessToken` from `TokenStorage`. If `failedAccessToken != null` and `currentAccessToken != null && currentAccessToken != failedAccessToken`, another caller already rotated the session — return `RefreshOutcome.refreshed(currentAccessToken)` immediately.
3. **Execute Rotation:** Read `refreshToken` from `TokenStorage`. If missing/blank, immediately call `sessionManager.forceExpireSession(reason)` and return `RefreshOutcome.expired(reason)`. Otherwise call `refreshDio.post('/api/v1/auth/refresh', data: {'refreshToken': refreshToken})`.
   - **On `200 OK` (`AuthResponse`):** Always update `accessToken` and `refreshToken` in `TokenStorage`. Only overwrite `userId`, `phoneNumber`, `fullName`, and `hasPin` if the returned field is **non-null** (preserving existing `hasPin` / user metadata if a partial response is returned). Resolve `_activeCompleter` with `RefreshOutcome.refreshed(newAccessToken)`.
   - **On Terminal Auth Failures (`401` such as `TOKEN_REUSE_DETECTED` / `INVALID_TOKEN_TYPE`, or `403 USER_LOCKED`):** Call `sessionManager.forceExpireSession(message)` -> clear `TokenStorage`, disconnect SSE, set `AuthSessionState.unauthenticated(bannerMessage)`, and resolve `_activeCompleter` with `RefreshOutcome.expired(message)`.
   - **On Transient Failures (network/timeout error, `5xx`, or `409 Conflict` when backend Redis lock is busy):** Do **NOT** clear `TokenStorage` or log the user out; resolve `_activeCompleter` with `RefreshOutcome.transientFailure(error)` so callers reject cleanly with a retryable error message without losing the valid session.

### 5.3. `AuthInterceptor` (`QueuedInterceptor`) Rules
- **Public / No-Auth Paths:** Do NOT attach `Authorization` and do NOT intercept 401 refresh for `/api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/refresh`, `/api/v1/payments/webhook`, `/api/v1/payments/vnpay-return`.
- **No-Refresh-on-401 Paths:** Attach `Authorization: Bearer $accessToken` in `onRequest` (so `AuthService.logout` can blacklist the access token `jti` in Redis), but **do NOT** trigger 401 token refresh in `onError` for `/api/v1/auth/logout`.
- **`onRequest`:** For all paths except Public/No-Auth paths, read `accessToken` from `TokenStorage` and set `Authorization: Bearer $accessToken` if present.
- **`onError` (`401 Unauthorized`):**
  1. Parse backend `ErrorResponse` (`code` / `errorCode`).
  2. **Immediate Session Termination Codes:** If `code` is `TOKEN_REUSE_DETECTED`, `TOKEN_REVOKED`, `INVALID_TOKEN_TYPE`, or `INVALID_TOKEN`, immediately call `forceExpireSession(...)` and reject without refreshing.
  3. **Infinite Loop Guard:** If `err.requestOptions.extra['_retried'] == true`, reject (and if `code == 'TOKEN_EXPIRED'`, call `forceExpireSession(...)`).
  4. **Refresh & Retry via `retryDio`:** Extract the Bearer token sent on the failed request (`failedToken`). Call `coordinator.refreshIfNeeded(failedAccessToken: failedToken)`.
     - If `RefreshOutcome.refreshed(newToken)`: clone `err.requestOptions`, set `headers['Authorization'] = 'Bearer $newToken'`, set `extra['_retried'] = true` (preserving original headers such as `Idempotency-Key`), and execute **`retryDio.fetch(clonedOptions)`** (NOT `apiDio.fetch(...)`!) before forwarding the result via `handler.resolve(...)` or `handler.reject(...)`.
     - If `RefreshOutcome.transientFailure(retryErr)`: reject via `handler.reject(err)` without clearing `TokenStorage`.
     - If `RefreshOutcome.expired`: reject via `handler.reject(err)` (router redirects to `/login`).

---

## 6. `Idempotency-Key` Lifecycle (Controller / Notifier Layer)

Transfers (`POST /api/v1/transfers`) and Withdrawals (`POST /api/v1/withdrawals`) require an `Idempotency-Key` header.
- **Generation:** `TransferNotifier` and `WithdrawNotifier` generate a `UUID v4` (`36` chars, compatible with `transactions.idempotency_key VARCHAR(64)` and `WithdrawalService`'s SHA-256 formatter) when the user initiates confirmation of a submission, storing it in `pendingIdempotencyKey` alongside a signature of the current form inputs.
- **Preservation (Safe Retries):**
  - During silent `401` refresh + retry inside `AuthInterceptor`, `clonedOptions.headers['Idempotency-Key']` is preserved verbatim.
  - If a request fails with `409 Conflict` ("Giao dịch đang được xử lý"), `5xx`, or a transient network/timeout error, the user's "Thử lại" action reuses the same `pendingIdempotencyKey`.
- **Regeneration (New Logical Attempt):**
  - If the user modifies any form input field (`receiverPhone`, `amount`, `bankCode`, `bankAccountNumber`, `accountHolderName`, `description`).
  - If the backend returns **any `4xx` error except `401` and `409`** (e.g., `400 INVALID_PIN`, `400 InsufficientBalance`, `400 PIN_NOT_SET`, `400 WalletException`, `404`), a fresh `UUID v4` is generated before the next submission.
  - On `200 OK` completion, clear `pendingIdempotencyKey`.

---

## 7. Real Physical Device Configuration & WebView VNPay Flow

### 7.1. Compile-Time Endpoint & Android Manifest Config
- `AppConfig.apiBaseUrl`:
  ```dart
  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://127.0.0.1:8080',
  );
  ```
- **Real Device Connection Modes:**
  - **USB Debugging (Recommended Default):** Run `adb reverse tcp:8080 tcp:8080` on the dev laptop, then `flutter run`. Inside the phone, `127.0.0.1:8080` (and `localhost:8080` inside WebView) forwards directly to the laptop's Spring Boot server.
  - **Wi-Fi LAN:** Run `flutter run --dart-define=API_BASE_URL=http://<LAPTOP_LAN_IP>:8080`.
- **Android Manifest Split:**
  - `android/app/src/main/AndroidManifest.xml`: declares `INTERNET` and `CAMERA`, sets `android:allowBackup="false"` inside `<application>` (prevents ADB/cloud backup data leakage and Android Keystore decryption failures across reinstalls for `flutter_secure_storage`), and does **NOT** set `usesCleartextTraffic="true"`.
  - `android/app/src/debug/AndroidManifest.xml`: sets `android:usesCleartextTraffic="true"` inside `<application>` for local HTTP `8080` testing only.

### 7.2. WebView VNPay (`VnpayWebViewScreen`) Flow
1. **Initiate Top-Up:** User submits `amount` (> `0`) and optional `description` -> `POST /api/v1/payments/topup` returns `{ transactionId, paymentUrl }`.
2. **Open WebView:** Navigate to `/topup/webview` with `transactionId` and `paymentUrl`. Render `WebViewWidget` (`webview_flutter`) with loading progress bar and confirm-close dialog if user presses Back mid-payment.
3. **Intercept `vnpay-return`:** Inside `NavigationDelegate.onNavigationRequest`:
   - Check `final uri = Uri.tryParse(request.url);`.
   - When `uri != null && uri.path.contains('/api/v1/payments/vnpay-return')`:
     - Return `NavigationDecision.prevent` so the WebView works identically under both `adb reverse` and LAN IP (where `http://localhost:8080` inside WebView would otherwise fail to resolve).
     - Forward the **raw unmodified query string** via `apiDio.get('/api/v1/payments/vnpay-return?${uri.query}')` (avoiding `queryParameters:` Map, which can re-encode values such as `+`/`%20` and break `vnp_SecureHash` verification) to obtain `{ validSignature, transactionId, responseCode, success, message }`.
4. **Reconcile Status & Webhook Caveat on Local Dev:**
   - Because VNPay Sandbox cannot reach a developer laptop's `localhost:8080` directly for the server-to-server IPN `POST /api/v1/payments/webhook` (unless exposed via ngrok/cloudflared), the result controller polls `GET /api/v1/transactions/{transactionId}` up to 3 times (`1s` interval):
     - If `status == COMPLETED` -> invalidates `walletProvider` + `historyProvider` + `notificationsProvider` and shows **"Nạp tiền thành công"**.
     - If gateway `responseCode == '00'` & `validSignature == true` while DB `status == PENDING` -> displays clear status **"VNPay đã xác nhận thanh toán – Đang chờ Webhook IPN hoàn tất giao dịch"** with a **"Kiểm tra lại trạng thái"** button (`GET /api/v1/transactions/{transactionId}`) and transaction ID for running the local IPN webhook script or ngrok tunnel.
     - Otherwise (`validSignature == false` or `responseCode != '00'`) -> displays failure reason from `vnpay-return` / transaction status.

---

## 8. SSE Real-Time Client & App Lifecycle Management

### 8.1. Connection & Stream Parsing (`SseNotificationService`)
- Opens `GET /api/v1/notifications/stream` on `sseDio` (`Options(responseType: ResponseType.stream, headers: {'Accept': 'text/event-stream', 'Cache-Control': 'no-cache', 'Authorization': 'Bearer $token'})`) with a `CancelToken`.
- Decodes raw TCP byte chunks safely via `stream.transform(utf8.decoder).transform(const LineSplitter())` (preserving multi-byte Vietnamese UTF-8 characters even when split across chunk boundaries), collecting `id:`, `event:`, and `data:` lines until an empty line dispatches an `SseFrame(id, event, data)`.
- Ignores keepalive/comment lines starting with `:`.
- Expects initial `event: connected` (`data: connected`) and subsequent `event: notification` with JSON matching `NotificationResponse` (`id`, `transactionId`, `type`, `title`, `message`, `read`, `createdAt`).

### 8.2. Lifecycle, Backoff & Reactive Cache Refresh
- **App Lifecycle Binding (`WidgetsBindingObserver`):**
  - Only active when `AuthSessionState` is authenticated.
  - On `AppLifecycleState.paused` or `detached`: immediately cancel `CancelToken` and close stream subscription (prevents orphaned connections counting toward `vwallet.sse.max-emitters-per-user: 5`).
  - On `AppLifecycleState.resumed`: reconnect SSE stream and trigger a silent catch-up refresh of `unreadCountProvider` and `walletProvider`.
- **Reconnect & Auth Policy:**
  - Network/EOF drop: reconnect with capped exponential backoff (`1s -> 2s -> 4s -> 8s -> 16s -> max 30s`, reset backoff after `event: connected`).
  - HTTP `401` on handshake: invoke `TokenRefreshCoordinator.refreshIfNeeded(failedAccessToken: token)`; if `refreshed`, reconnect immediately; if `expired`, invoke `forceExpireSession(...)`; if `transientFailure`, wait with backoff and retry without clearing `TokenStorage`.
- **On `event: notification` Received:**
  1. Deduplicate and prepend `NotificationResponse` into `notificationsProvider` state (if loaded).
  2. Invalidate `unreadCountProvider`, `walletProvider`, and `transactionHistoryProvider` so the Home balance, notification bell badge, and recent transactions update immediately.
  3. Trigger an in-app notification banner/snackbar with title & message (tappable to open `/notifications`).

---

## 9. Complete Backend API Mapping, Screens & UX Flows

### 9.1. Backend API Contract Matrix (Phase A–C Alignment)

| Feature | Endpoint | Request / Params | Key Response Fields |
|---------|----------|------------------|---------------------|
| Register | `POST /api/v1/auth/register` | `{ phoneNumber, fullName, password }` | `AuthResponse` (`userId`, `phoneNumber`, `fullName`, `hasPin`, `accessToken`, `refreshToken`, `expiresIn`) |
| Login | `POST /api/v1/auth/login` | `{ phoneNumber, password }` | `AuthResponse` |
| Refresh | `POST /api/v1/auth/refresh` | `{ refreshToken }` | `AuthResponse` |
| Logout | `POST /api/v1/auth/logout` | `{ refreshToken }` (optional body) + Bearer | `{ success, message }` |
| Profile | `GET /api/v1/users/me` | Bearer | `UserMeResponse` (`userId`, `phoneNumber`, `fullName`, `hasPin`, `status`, `createdAt`) |
| Setup PIN | `POST /api/v1/users/pin` | `{ pin, confirmPin }` (`^[0-9]{6}$`) | `{ success, message }` |
| Lookup | `GET /api/v1/users/lookup?phone=...` | Query `phone` | `UserLookupResponse` (`phoneNumber`, `fullName`, `isSelf`, `canReceive`) |
| My Wallet | `GET /api/v1/wallets/me` | Bearer | `WalletResponse` (`id`, `walletId`, `userId`, `balance`, `currency`, `status`, `updatedAt`) |
| Topup Init | `POST /api/v1/payments/topup` | `{ amount, description }` | `TopupResponse` (`transactionId`, `paymentUrl`) |
| VNPay Return | `GET /api/v1/payments/vnpay-return` | VNPay query params (`vnp_*`) | `{ validSignature, transactionId, responseCode, success, message }` |
| Tx Status | `GET /api/v1/transactions/{id}` | Path `id` | `TransactionResponse` (`id`, `transactionId`, `amount`, `type`, `status`, `description`, `externalRef`, `createdAt`) |
| Tx History | `GET /api/v1/transactions` | `page`, `size`, `type`, `status`, `direction` (`ALL`/`IN`/`OUT`), `from`, `to` (ISO-8601) | `PageResponse<TransactionHistoryItemResponse>` |
| P2P Transfer | `POST /api/v1/transfers` | Header `Idempotency-Key`; Body `{ receiverPhone, amount, pin, description }` | `TransferResponse` (`success`, `transactionId`, `senderWalletId`, `receiverWalletId`, `amount`, `status`, `createdAt`) |
| Withdraw | `POST /api/v1/withdrawals` | Header `Idempotency-Key`; Body `{ bankCode, bankAccountNumber, accountHolderName, amount, pin, description }` | `WithdrawalResponse` (`success`, `withdrawalId`, `transactionId`, `bankCode`, `bankAccountNumber`, `accountHolderName`, `amount`, `status`, `bankRef`, `balanceAfter`, `createdAt`) |
| QR Generate | `POST /api/v1/qr/generate` | `{ type: "STATIC"\|"DYNAMIC", amount?, description? }` | `QrGenerateResponse` (`qrContent`, `type`, `receiverPhone`, `receiverName`, `amount`, `description`, `expiresAt`) |
| QR Parse | `POST /api/v1/qr/parse` | `{ qrContent }` | `QrParseResponse` (`type`, `receiverPhone`, `receiverName`, `amount`, `description`, `isSelf`, `canTransfer`, `expiresAt`) |
| Notifications | `GET /api/v1/notifications` | `page`, `size`, `unreadOnly` | `PageResponse<NotificationResponse>` |
| Unread Count | `GET /api/v1/notifications/unread-count` | Bearer | `{ unreadCount }` |
| Mark Read | `PATCH /api/v1/notifications/{id}/read` | Path `id` | `NotificationResponse` |
| Read All | `PATCH /api/v1/notifications/read-all` | Bearer | `{ updatedCount }` |
| SSE Stream | `GET /api/v1/notifications/stream` | Bearer (`Accept: text/event-stream`) | `event:connected`, `event:notification` |

### 9.2. Key Screens & UX Specifications

1. **Bootstrap & Auth Screens (`/splash`, `/login`, `/register`):**
   - **Cold-Start Bootstrap (`AuthSessionState.initializing`):** On app launch, `AuthSessionNotifier` starts in `initializing` while reading `TokenStorage` (and optionally validating profile); `GoRouter` holds on `/splash` during `initializing` so authenticated users never see a `/login` screen flicker before entering `/home`.
   - Client validation matching backend regex: Vietnamese phone `^(\+84|0)[35789][0-9]{8}$`, password `>= 8` chars containing at least 1 letter & 1 digit, fullName `2–100` chars.
   - Displays session-expiry banner if redirected by `forceExpireSession(...)`.
2. **Home Dashboard (`/home`):**
   - Top bar: User greeting (`fullName`), unread notification bell badge (`unreadCountProvider`).
   - Balance Card: Formatted VND balance (`balance` with hide/reveal eye toggle), wallet status badge, pull-to-refresh (`GET /api/v1/wallets/me` + `GET /api/v1/users/me`).
   - Quick Action Grid: `Nạp tiền` (`/topup`), `Chuyển tiền` (`/transfer`), `Rút tiền` (`/withdraw`), `Mã QR` (`/qr`).
   - Recent Transactions section (top 5 from `GET /api/v1/transactions?page=0&size=5`) + "Xem tất cả" link to `/history`.
   - Persistent banner on Home if `hasPin == false`: "Bạn chưa thiết lập mã PIN giao dịch" linking straight to `/pin/setup`.
3. **Reusable 6-Digit PIN UX (`PinSetupScreen` & `PinModalSheet`):**
   - **Pre-flight Check:** Before confirming Transfer or Withdrawal, check cached `hasPin` (refreshing `/api/v1/users/me` if unknown). If `hasPin == false`, push `/pin/setup` instead of opening the PIN keypad; once setup succeeds (`200 OK`), update `hasPin = true` in `TokenStorage`/state and resume seamlessly.
   - **Defensive Error Check:** If `POST /api/v1/transfers` or `/withdrawals` ever returns `code == PIN_NOT_SET`, close modal and navigate to `/pin/setup`.
   - **`PinModalSheet`:** Custom 0–9 numeric keypad + backspace + 6 masked dots + transaction summary header (amount + recipient/bank). Auto-submits upon entering 6th digit; on `400 INVALID_PIN`, triggers inline error message, clears dots, and prompts `Idempotency-Key` regeneration.
4. **P2P Transfer (`/transfer`):**
   - Phone input with lookup button / debounced lookup (`GET /api/v1/users/lookup?phone=...`).
   - Shows recipient card (`fullName`, `phoneNumber`). Blocks submit with inline explanation if `isSelf == true` ("Không thể tự chuyển tiền cho chính mình") or `canReceive == false` ("Tài khoản hoặc ví người nhận hiện không thể nhận tiền").
   - Gracefully handles `400 INVALID_PHONE_FORMAT`, `404 USER_NOT_FOUND`, and `429 RATE_LIMIT_EXCEEDED`.
   - Supports pre-filled immutable amount/phone/description when arriving from a `DYNAMIC` QR scan, or pre-filled phone with editable amount from a `STATIC` QR scan.
5. **Cash-Out / Bank Withdrawal (`/withdraw`):**
   - Bank selector (`VCB`, `TCB`, `MB`, `BIDV`, `CTG`, `ACB`, `TPB`, etc. matching `^[A-Z0-9_]{2,20}$`), account number (`^[0-9]{6,20}$`), account holder name (`2–100` chars), amount (`10,000`–`50,000,000` VND with quick-pick chips), optional description (`<= 255` chars).
   - Opens `PinModalSheet` -> calls `POST /api/v1/withdrawals` with `Idempotency-Key` -> navigates to Receipt displaying `withdrawalId`, `transactionId`, `bankCode`, `bankAccountNumber`, `bankRef`, and `balanceAfter`.
6. **QR Hub (`/qr`) — Two Tabs:**
   - **Tab 1 ("Mã QR của tôi"):** Switch between `STATIC` and `DYNAMIC` (requires amount `1,000`–`50,000,000` VND + optional description `<= 255` chars). Calls `POST /api/v1/qr/generate`, renders `qrContent` via `QrImageView` (`qr_flutter`), displays live expiry countdown when `expiresAt != null`, and provides a "Sao chép chuỗi QR" button for easy testing.
   - **Tab 2 ("Quét mã"):** Live camera scanner (`mobile_scanner`) with flash toggle and one-shot lock (pauses scan while parsing). Includes an accessible **"Dán mã QR"** button/bottom-sheet accepting raw `vwallet://transfer?...` text for physical-device testing without a second screen. Calls `POST /api/v1/qr/parse`, maps `INVALID_QR_CODE` / `EXPIRED_QR_CODE` / `USER_NOT_FOUND` / `!canTransfer` / `isSelf` clearly, and routes eligible transfers to `/transfer`.
7. **Transaction History (`/history`) & Notifications (`/notifications`):**
   - **History:** Infinite scroll `ListView` backed by `PageResponse`, filter chips (`type`, `direction`: `ALL`/`IN`/`OUT`, `status`, date range `from`–`to`), `IN` (`+` green) vs `OUT` (`-` red) styling, bottom-sheet detail via `GET /api/v1/transactions/{id}`.
   - **Notifications:** Paginated list with "Tất cả" / "Chưa đọc" (`unreadOnly`) tabs, tap item -> `PATCH /api/v1/notifications/{id}/read`, header action "Đọc tất cả" -> `PATCH /api/v1/notifications/read-all`.
8. **Profile & Security (`/profile`):**
   - Displays `userId`, `fullName`, `phoneNumber`, `status`, PIN status (`Đã thiết lập` / `Chưa thiết lập` with button to `/pin/setup` if unset), current API server URL (`AppConfig.apiBaseUrl`), and **Best-Effort Logout**: sends `POST /api/v1/auth/logout` (excluded from 401 auto-refresh) and always clears `TokenStorage`, disconnects SSE, and transitions `AuthSessionState` to unauthenticated inside `finally` even if the network or token fails.

---

## 10. Error Handling Strategy

Backend errors (`ErrorResponse`: `status`, `error`, `message`, `code`, `errorCode`, `timestamp`) are mapped centrally in `ApiException.fromDioException(DioException)`:
- Prefer `code ?? errorCode` for machine handling (`TOKEN_EXPIRED`, `TOKEN_REUSE_DETECTED`, `TOKEN_REVOKED`, `PIN_NOT_SET`, `INVALID_PIN`, `INVALID_PHONE_FORMAT`, `USER_NOT_FOUND`, `USER_LOCKED`, `INVALID_QR_CODE`, `EXPIRED_QR_CODE`, `RATE_LIMIT_EXCEEDED`, `CONFLICT`).
- Fall back to `message` from backend for user-facing Vietnamese feedback (note that some exceptions like `InsufficientBalanceException`, `WalletException`, and `IdempotencyConflictException` populate `message` with `code = null`).
- Map connectivity timeouts / socket errors cleanly to user-friendly Vietnamese retry prompts ("Không thể kết nối máy chủ. Kiểm tra mạng hoặc adb reverse.").

---

## 11. Verification & Testing Plan

### 11.1. Automated Tests (`flutter test`)
1. **Unit Tests:**
   - `token_refresh_coordinator_test.dart`: verifies 3 concurrent `refreshIfNeeded` calls trigger **exactly 1** HTTP `/refresh` call; verifies stale-token shortcut when `TokenStorage` already updated; verifies `hasPin`/profile fields are preserved on partial refresh response; verifies `TOKEN_REUSE_DETECTED`/`401`/`403 USER_LOCKED` clears storage while transient network/`5xx`/`409` failures keep `TokenStorage` intact.
   - `auth_interceptor_test.dart`: verifies `retryDio` executes retries (and retry `400`/`500` does not deadlock `apiDio`'s queue), `Idempotency-Key` preservation across 401 retry, `_retried = true` loop prevention, public paths omit `Authorization` and bypass refresh, and `/api/v1/auth/logout` attaches `Authorization` while bypassing 401 refresh.
   - `idempotency_notifier_test.dart`: verifies `TransferNotifier` & `WithdrawNotifier` preserve UUID on `401`/`409`/`5xx`/timeout and regenerate UUID on input edit or any other `4xx` error.
   - `sse_parser_test.dart`: verifies multi-byte Vietnamese UTF-8 strings split across byte chunks assemble cleanly without corruption, comment ignoring, and `SseNotificationService` disconnects immediately on `AppLifecycleState.paused`.
   - `vnpay_uri_helper_test.dart`: verifies `/api/v1/payments/vnpay-return` URL detection and exact raw `uri.query` preservation (no re-encoding).
2. **Widget Tests:**
   - `pin_modal_sheet_test.dart`: verifies 6-digit entry, backspace, masked dots, and error reset.
   - `qr_hub_screen_test.dart`: verifies manual QR paste submission and navigation into pre-filled `TransferScreen`.
   - `login_screen_test.dart`: verifies client validation and session-expired banner rendering.

### 11.2. Real-Device Smoke Verification (`flutter run`)
- Connect Android device via USB with `adb reverse tcp:8080 tcp:8080`.
- Verify live flows against local Docker (`MySQL + Redis + Kafka`) + `ewallet-backend`:
  1. Register -> Setup 6-digit PIN -> Home Dashboard balance `0 ₫`.
  2. Top-Up via `VnpayWebViewScreen` (redirect intercepted cleanly) + local IPN webhook trigger -> Kafka pushes `TOPUP_SUCCESS` over SSE -> Home balance updates live.
  3. Generate `DYNAMIC` QR -> paste/scan on another account -> P2P Transfer with PIN -> verify `TRANSFER_SENT` / `TRANSFER_RECEIVED` SSE & History `OUT` / `IN`.
  4. Withdraw to mock bank -> verify `WITHDRAW_SUCCESS` & `bankRef` receipt.
  5. Force `accessToken` expiry (wait or revoke) & fire concurrent dashboard calls -> verify seamless single-flight auto-refresh without user logout; test logging in on a second client -> verify old phone receives `TOKEN_REUSE_DETECTED` and redirects to `/login`.
