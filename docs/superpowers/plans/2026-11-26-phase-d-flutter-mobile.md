# Phase D (Flutter Mobile Client) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete Flutter Mobile client in `ewallet-mobile/` consuming all Phase A–C `ewallet-backend` APIs with deadlock-free single-flight JWT refresh, 6-digit PIN setup & entry, VNPay WebView payment, signed QR generate/scan/paste, P2P transfer, bank cash-out, paginated history, and UTF-8 chunk-safe SSE real-time notifications.

**Architecture:** Feature-First organization (`lib/core/` + `lib/features/<feature>/{data,application,presentation}/`) backed by `flutter_riverpod` for reactive state, a single `GoRouter` instance driven by `refreshListenable` for auth gating, and four isolated `Dio` instances (`apiDio`, `refreshDio`, `retryDio`, `sseDio`) coordinating Single-Device RTR + 30s Grace Period via a shared `Completer`-based `TokenRefreshCoordinator`.

**Tech Stack:** Flutter `3.47.5` / Dart `3.13.4`, `flutter_riverpod`, `go_router`, `dio`, `flutter_secure_storage`, `mobile_scanner`, `qr_flutter`, `webview_flutter`, `uuid`, `intl`, `flutter_test`, `mocktail`.

**Spec:** [Phase D Flutter Mobile Design Specification](../specs/2026-11-26-phase-d-flutter-mobile-design.md)

## Global Constraints
- **Target Runtime & SDK:** Flutter `3.47.5` / Dart `3.13.4` inside `ewallet-mobile/`, verified via `flutter test` and real physical Android device (`adb reverse tcp:8080 tcp:8080`, default `API_BASE_URL=http://127.0.0.1:8080`, configurable via `--dart-define=API_BASE_URL=...`).
- **Four Isolated Dio Instances:** `apiDio` (`QueuedInterceptor`), `refreshDio` (no `AuthInterceptor`, calls `/api/v1/auth/refresh` only), `retryDio` (no `AuthInterceptor`, executes retried requests inside `AuthInterceptor.onError()` wrapped in `try/catch (DioException retryErr)` rejecting `retryErr`), and `sseDio` (`receiveTimeout: Duration.zero`).
- **Refresh Coordinator Integrity:** `TokenRefreshCoordinator` always resets `_activeCompleter = null` in `finally`; calls `forceExpireSession` ONLY on `401`, `403 USER_LOCKED`, or missing `refreshToken` (keeps `TokenStorage` on network/`5xx`/`409`); preserves `hasPin`/user metadata when refresh returns `null` fields.
- **Router & Secure Storage Safety:** Instantiate `GoRouter` once via `refreshListenable` with `AuthSessionState.initializing` holding on `/splash`; wrap `FlutterSecureStorage` reads in `try/catch` calling `deleteAll()` on Android Keystore errors; check `response?.data is Map` in `ApiException.fromDioException`.
- **Logout & Idempotency Rules:** `/api/v1/auth/logout` attaches `Authorization` in `onRequest`, skips 401 refresh in `onError`, and always cleans storage/SSE in `finally`. `Idempotency-Key` (`UUID v4`) regenerates only on form edit or any `4xx` except `401` and `409`.
- **VNPay, SSE & Android Security:** Forward raw `uri.query` string (`'/api/v1/payments/vnpay-return?${uri.query}'`); decode SSE with `stream.transform(utf8.decoder).transform(const LineSplitter())`; disconnect SSE on `AppLifecycleState.paused`/`detached`; set `android:allowBackup="false"` in `main/AndroidManifest.xml` and `usesCleartextTraffic="true"` strictly in `debug/AndroidManifest.xml`.

## Review Focus
- **Post-refresh retry returning `400`/`500`:** Must bypass `apiDio`'s `QueuedInterceptor` via `retryDio` and call `handler.reject(retryErr)` so the caller sees the `400`/`500` business error instead of the original `401` -> pinned in Task 2 (`auth_interceptor_test.dart`).
- **Corrupted Android Keystore or null `hasPin` on refresh:** `TokenStorage` must wipe via `deleteAll()` and return `null` instead of crashing at boot, and `TokenRefreshCoordinator` must not reset `hasPin=true` to `false` -> pinned in Task 2 (`token_storage_test.dart`, `token_refresh_coordinator_test.dart`).
- **Cold-start `/login` flicker & router stack wipe:** Opening the app with saved credentials must stay on `/splash` during `initializing` and never recreate `GoRouter` -> pinned in Task 3 (`app_router_test.dart`).
- **VNPay `vnp_OrderInfo` special/space encoding (`+` vs `%20`):** Intercepted `vnpay-return` URI must forward exact raw `uri.query` rather than `queryParameters` Map -> pinned in Task 5 (`vnpay_uri_helper_test.dart`).
- **Multi-byte Vietnamese UTF-8 split across SSE byte chunks:** `SseParser` must assemble split multi-byte characters via stateful `utf8.decoder` without `FormatException` or garbled text -> pinned in Task 8 (`sse_parser_test.dart`).

---

### Task 1: Flutter Scaffold, Android Manifests, Config, Theme & Formatters

**Files:**
- Create: `ewallet-mobile/pubspec.yaml` (via `flutter create` + dependency setup)
- Create: `ewallet-mobile/android/app/src/debug/AndroidManifest.xml`
- Modify: `ewallet-mobile/android/app/src/main/AndroidManifest.xml`
- Create: `ewallet-mobile/lib/core/config/app_config.dart`
- Create: `ewallet-mobile/lib/core/theme/app_theme.dart`
- Create: `ewallet-mobile/lib/core/widgets/currency_formatter.dart`
- Create: `ewallet-mobile/lib/core/widgets/status_badge.dart`
- Test: `ewallet-mobile/test/core/widgets/currency_formatter_test.dart`
- Test: `ewallet-mobile/test/core/config/app_config_test.dart`

**Interfaces:**
- Produces:
  - `AppConfig.apiBaseUrl` (`String`), `AppConfig.connectTimeout`, `AppConfig.receiveTimeout`, `AppConfig.isValidVnPhone(String)`
  - `AppTheme.lightTheme()` (`ThemeData`)
  - `CurrencyFormatter.formatVnd(int amount)` (`String`, e.g. `"1.000.000 ₫"`)
  - `StatusBadge(status: TransactionStatus)` (`Widget`)

- [ ] **Step 1: Scaffold Flutter app & configure Android manifests + dependencies**
  Run `flutter create --org com.meme.vwallet --project-name vwallet_mobile --platforms=android,ios .` inside `ewallet-mobile/`, remove `.gitkeep`, add dependencies (`flutter_riverpod`, `go_router`, `dio`, `flutter_secure_storage`, `mobile_scanner`, `qr_flutter`, `webview_flutter`, `uuid`, `intl`; dev: `mocktail`) in `pubspec.yaml`, add `INTERNET`, `CAMERA`, and `android:allowBackup="false"` to `android/app/src/main/AndroidManifest.xml`, and create `android/app/src/debug/AndroidManifest.xml` with `android:usesCleartextTraffic="true"`.

- [ ] **Step 2: Write the failing unit tests for `AppConfig` and `CurrencyFormatter`**
```dart
test('AppConfig defaults apiBaseUrl to http://127.0.0.1:8080 and validates VN phones', () {
  expect(AppConfig.apiBaseUrl, 'http://127.0.0.1:8080');
  expect(AppConfig.isValidVnPhone('0901234567'), isTrue);
  expect(AppConfig.isValidVnPhone('+84987654321'), isTrue);
  expect(AppConfig.isValidVnPhone('0000000000'), isFalse);
});

test('CurrencyFormatter formats VND with dot separators', () {
  expect(CurrencyFormatter.formatVnd(1000000), '1.000.000 ₫');
  expect(CurrencyFormatter.formatVnd(0), '0 ₫');
});
```

- [ ] **Step 3: Run tests to verify they fail**
  Run: `flutter test test/core/config/app_config_test.dart test/core/widgets/currency_formatter_test.dart` (from `ewallet-mobile/`)
  Expected: FAIL with missing `AppConfig` and `CurrencyFormatter`.

- [ ] **Step 4: Implement `AppConfig`, `AppTheme`, `CurrencyFormatter`, and `StatusBadge`**
  Define `String.fromEnvironment('API_BASE_URL', defaultValue: 'http://127.0.0.1:8080')`, regex `^(\+84|0)[35789][0-9]{8}$`, Material 3 theme (`#0F766E` teal primary, clean tabular numerals), `NumberFormat('#,##0', 'vi_VN')` formatting `1.000.000 ₫`, and `StatusBadge` for `COMPLETED`/`PENDING`/`FAILED`.

- [ ] **Step 5: Run tests and `flutter analyze` to verify pass**
  Run: `flutter test test/core/config/app_config_test.dart test/core/widgets/currency_formatter_test.dart; flutter analyze`
  Expected: PASS with 0 analyzer issues.

- [ ] **Step 6: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): scaffold ewallet-mobile with manifests, config, theme, and formatters"
  ```

---

### Task 2: `TokenStorage`, `ApiException`, 4 Dio Instances & Single-Flight `AuthInterceptor`

**Files:**
- Create: `ewallet-mobile/lib/core/storage/token_storage.dart`
- Create: `ewallet-mobile/lib/core/network/api_exception.dart`
- Create: `ewallet-mobile/lib/core/network/token_refresh_coordinator.dart`
- Create: `ewallet-mobile/lib/core/network/auth_interceptor.dart`
- Create: `ewallet-mobile/lib/core/network/dio_providers.dart`
- Test: `ewallet-mobile/test/core/storage/token_storage_test.dart`
- Test: `ewallet-mobile/test/core/network/api_exception_test.dart`
- Test: `ewallet-mobile/test/core/network/token_refresh_coordinator_test.dart`
- Test: `ewallet-mobile/test/core/network/auth_interceptor_test.dart`

**Interfaces:**
- Consumes: `AppConfig` (Task 1)
- Produces:
  - `StoredSession(String accessToken, String refreshToken, int? userId, String? phoneNumber, String? fullName, bool? hasPin)`
  - `TokenStorage`: `Future<StoredSession?> readSession()`, `Future<void> saveSession(...)`, `Future<void> updateTokens({required String accessToken, required String refreshToken, int? userId, String? phoneNumber, String? fullName, bool? hasPin})`, `Future<void> updateHasPin(bool hasPin)`, `Future<void> clear()`
  - `ApiException implements Exception`: `int? statusCode`, `String? code`, `String message`, `factory ApiException.fromDioException(DioException err)`
  - `RefreshOutcome` (`refreshed(String accessToken)`, `expired(String message)`, `transientFailure(Object error)`)
  - `TokenRefreshCoordinator`: `Future<RefreshOutcome> refreshIfNeeded({required String? failedAccessToken})`
  - Riverpod providers: `apiDioProvider`, `refreshDioProvider`, `retryDioProvider`, `sseDioProvider`, `tokenStorageProvider`, `tokenRefreshCoordinatorProvider`

- [ ] **Step 1: Write the failing tests for `TokenStorage`, `ApiException`, `TokenRefreshCoordinator`, and `AuthInterceptor`**
  - `token_storage_test.dart`:
    - `readSession_onKeystorePlatformException_deletesAllAndReturnsNull`
    - `updateTokens_preservesExistingHasPinWhenNewValueIsNull`
  - `api_exception_test.dart`:
    - `fromDioException_withResponseBodyStreamData_doesNotThrowTypeErrorAndFallsBackGracefully`
    - `fromDioException_extractsCodeOrErrorCodeAndMessageFromJsonMap`
  - `token_refresh_coordinator_test.dart`:
    - `refreshIfNeeded_threeConcurrentCalls_invokesRefreshEndpointExactlyOnceAndResetsCompleter`
    - `refreshIfNeeded_staleAccessTokenAlreadyRotated_returnsNewTokenWithoutCallingRefresh`
    - `refreshIfNeeded_on500OrNetworkOr409_returnsTransientFailureAndDoesNotClearStorage`
    - `refreshIfNeeded_on401Or403UserLocked_clearsStorageAndTriggersForceExpire`
  - `auth_interceptor_test.dart`:
    - `onError_401TokenExpired_refreshesOnceAndRetriesViaRetryDioPreservingIdempotencyKey`
    - `onError_retryReturns400_rejectsWith400RetryErrorNot401AndDoesNotDeadlock`
    - `onError_retriedRequestGetsSecond401_rejectsWithoutLooping`
    - `onError_tokenReuseDetected_forcesSessionExpireWithoutCallingRefresh`
    - `logoutPath_attachesBearerOnRequestButSkips401RefreshOnError`
    - `publicPaths_omitBearerOnRequestAndSkipRefreshOnError`

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/core/storage/ test/core/network/`
  Expected: FAIL with missing classes/files.

- [ ] **Step 3: Implement `TokenStorage` and `ApiException`**
  - In `TokenStorage`: wrap every `secureStorage.read(...)` / `readAll()` inside `try/catch`; on any `Exception` (Android `PlatformException` / Keystore BadPaddingException), call `await secureStorage.deleteAll()` best-effort and return `null`.
  - In `ApiException.fromDioException`: verify `final data = err.response?.data; if (data is Map)` before reading `data['code'] ?? data['errorCode']` and `data['message']`; non-Map bodies (`ResponseBody`, `String`, `null`) fall back safely using status code defaults.

- [ ] **Step 4: Implement `TokenRefreshCoordinator`, `AuthInterceptor`, and `dio_providers.dart`**
  - In `TokenRefreshCoordinator`: use `Completer<RefreshOutcome>? _activeCompleter;` with `try { ... } finally { _activeCompleter = null; }` so `_activeCompleter` always clears even on unexpected throw. Call `refreshDio.post('/api/v1/auth/refresh', data: {'refreshToken': refreshToken})`. Call `onForceExpire(message)` ONLY on missing `refreshToken`, `401`, or `403` (`USER_LOCKED`). Never clear `TokenStorage` on `409`, `5xx`, or `connectionTimeout`/`receiveTimeout`/`connectionError`.
  - In `AuthInterceptor extends QueuedInterceptor`:
    - Split `_publicPaths` (`/api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/refresh`, `/api/v1/payments/webhook`, `/api/v1/payments/vnpay-return`) vs `_noRefreshOn401Paths` (`/api/v1/auth/logout`).
    - On `401` retry: clone `RequestOptions` with updated `Authorization` and `extra['_retried'] = true`, run `try { final res = await retryDio.fetch<dynamic>(cloned); handler.resolve(res); } on DioException catch (retryErr) { handler.reject(retryErr); }`.
  - In `dio_providers.dart`: provide 4 distinct `Dio` instances (`apiDio`, `refreshDio`, `retryDio`, `sseDio`) where ONLY `apiDio` has `AuthInterceptor`.

- [ ] **Step 5: Run tests to verify pass**
  Run: `flutter test test/core/storage/ test/core/network/; flutter analyze`
  Expected: PASS with 0 errors.

- [ ] **Step 6: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add secure token storage, ApiException, 4 Dio instances, and single-flight AuthInterceptor"
  ```

---

### Task 3: Single-Instance `GoRouter` (`initializing`), Auth Flows & Best-Effort Logout

**Files:**
- Create: `ewallet-mobile/lib/features/auth/data/auth_models.dart`
- Create: `ewallet-mobile/lib/features/auth/data/auth_repository.dart`
- Create: `ewallet-mobile/lib/features/auth/application/auth_session_notifier.dart`
- Create: `ewallet-mobile/lib/core/routing/app_router.dart`
- Create: `ewallet-mobile/lib/features/auth/presentation/splash_screen.dart`
- Create: `ewallet-mobile/lib/features/auth/presentation/login_screen.dart`
- Create: `ewallet-mobile/lib/features/auth/presentation/register_screen.dart`
- Create: `ewallet-mobile/lib/features/auth/presentation/profile_screen.dart`
- Modify: `ewallet-mobile/lib/app.dart`
- Modify: `ewallet-mobile/lib/main.dart`
- Test: `ewallet-mobile/test/core/routing/app_router_test.dart`
- Test: `ewallet-mobile/test/features/auth/auth_session_notifier_test.dart`
- Test: `ewallet-mobile/test/features/auth/login_screen_test.dart`

**Interfaces:**
- Consumes: `TokenStorage`, `apiDioProvider`, `tokenRefreshCoordinatorProvider` (Task 2)
- Produces:
  - `AuthSessionStatus` enum (`initializing`, `authenticated`, `unauthenticated`)
  - `AuthSessionState(status, userId, phoneNumber, fullName, hasPin, sessionExpiredMessage)`
  - `authSessionProvider` (`NotifierProvider<AuthSessionNotifier, AuthSessionState>`)
  - `AuthSessionNotifier`: `bootstrap()`, `login(phone, password)`, `register(phone, fullName, password)`, `logout()`, `forceExpireSession(String message)`, `markHasPinSet()`
  - `appRouterProvider` (`Provider<GoRouter>` created once using `refreshListenable`)

- [ ] **Step 1: Write the failing tests for router, session lifecycle, and login validation**
  - `auth_session_notifier_test.dart`:
    - `initialState_isInitializing_thenTransitionsToAuthenticatedIfStoredSessionExists`
    - `logout_clearsStorageAndTransitionsToUnauthenticatedEvenWhenApiThrows401`
    - `forceExpireSession_clearsStorageAndSetsExpiredMessage`
  - `app_router_test.dart`:
    - `coldStart_staysOnSplashDuringInitializingWithoutFlashingLogin`
    - `routerInstance_isCreatedOnceAcrossAuthStateUpdatesViaRefreshListenable`
  - `login_screen_test.dart`:
    - `rendersSessionExpiredBannerAndRejectsInvalidPhoneOrShortPassword`

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/core/routing/app_router_test.dart test/features/auth/`
  Expected: FAIL with missing files.

- [ ] **Step 3: Implement `AuthRepository`, `AuthSessionNotifier`, and single-instance `GoRouter`**
  - `AuthSessionNotifier` starts at `AuthSessionState.initializing()`, reads `TokenStorage` in `bootstrap()`, and connects `forceExpireSession` callback to `TokenRefreshCoordinator`.
  - `logout()` runs `try { await authRepository.logout(refreshToken: currentRefresh); } catch (_) {} finally { await tokenStorage.clear(); await onBeforeClearSession?.call(); state = const AuthSessionState.unauthenticated(); }` so storage + SSE always reset.
  - `appRouterProvider` builds `GoRouter` **once** inside a Riverpod `Provider` using a `ChangeNotifier` bridge (`_RouterRefreshNotifier`) notified on `ref.listen(authSessionProvider, ...)`. Routes `/splash` while `initializing`, `/login` / `/register` when `unauthenticated`, and `/home` when `authenticated` (with stub placeholder routes for `/pin/setup`, `/topup`, `/transfer`, `/withdraw`, `/qr`, `/history`, `/notifications`, `/profile` replaced in Tasks 4–8).

- [ ] **Step 4: Implement `SplashScreen`, `LoginScreen`, `RegisterScreen`, `ProfileScreen`, `app.dart`, and `main.dart`**
  Validate phone (`AppConfig.isValidVnPhone`), password (`>= 8` chars, `(?=.*[A-Za-z])(?=.*\d)`), fullName (`2–100` chars). `ProfileScreen` displays `userId`, `fullName`, `phoneNumber`, `hasPin` badge + link to `/pin/setup`, `AppConfig.apiBaseUrl`, and Logout button.

- [ ] **Step 5: Run tests to verify pass**
  Run: `flutter test test/core/routing/app_router_test.dart test/features/auth/; flutter analyze`
  Expected: PASS.

- [ ] **Step 6: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add single-instance GoRouter, AuthSession bootstrap, auth screens, and best-effort logout"
  ```

---

### Task 4: 6-Digit PIN Setup, Reusable `PinModalSheet` & Home Wallet Dashboard

**Files:**
- Create: `ewallet-mobile/lib/features/pin/data/pin_repository.dart`
- Create: `ewallet-mobile/lib/features/pin/presentation/pin_setup_screen.dart`
- Create: `ewallet-mobile/lib/core/widgets/pin_modal_sheet.dart`
- Create: `ewallet-mobile/lib/features/home_wallet/data/wallet_models.dart`
- Create: `ewallet-mobile/lib/features/home_wallet/data/wallet_repository.dart`
- Create: `ewallet-mobile/lib/features/home_wallet/application/wallet_provider.dart`
- Create: `ewallet-mobile/lib/features/home_wallet/presentation/home_screen.dart`
- Modify: `ewallet-mobile/lib/core/routing/app_router.dart`
- Test: `ewallet-mobile/test/core/widgets/pin_modal_sheet_test.dart`
- Test: `ewallet-mobile/test/features/pin/pin_setup_screen_test.dart`
- Test: `ewallet-mobile/test/features/home_wallet/home_screen_test.dart`

**Interfaces:**
- Consumes: `apiDioProvider`, `authSessionProvider`, `CurrencyFormatter`, `StatusBadge` (Tasks 1–3)
- Produces:
  - `PinRepository`: `Future<void> setupPin({required String pin, required String confirmPin})` (`POST /api/v1/users/pin`)
  - `showPinModalSheet(BuildContext context, {required String title, required String subtitle, required Future<String?> Function(String pin) onSubmitPin})`: returns `Future<bool>` (`true` when accepted, displays returned inline error and resets 6 dots on mismatch)
  - `WalletResponse` (`id`, `walletId`, `userId`, `balance`, `currency`, `status`, `updatedAt`)
  - `walletProvider` (`AsyncNotifierProvider<WalletNotifier, WalletResponse>`)
  - `recentTransactionsProvider` (`FutureProvider`)

- [ ] **Step 1: Write the failing tests for `PinModalSheet`, `PinSetupScreen`, and `HomeScreen`**
  - `pin_modal_sheet_test.dart`: tapping 6 digits auto-invokes `onSubmitPin`; backspace removes digit; returning an error string displays the message and clears dots back to 0.
  - `pin_setup_screen_test.dart`: mismatch between `pin` and `confirmPin` shows error without calling API; matching 6 digits calls `POST /api/v1/users/pin` and calls `authSessionNotifier.markHasPinSet()`.
  - `home_screen_test.dart`: displays formatted balance (`1.000.000 ₫`), hide/reveal eye toggle masks balance (`••••••••`), shows `"Chưa thiết lập mã PIN"` banner when `hasPin == false`, and renders quick actions + notification bell button.

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/core/widgets/pin_modal_sheet_test.dart test/features/pin/ test/features/home_wallet/`
  Expected: FAIL with missing files.

- [ ] **Step 3: Implement `PinRepository`, `PinSetupScreen`, `PinModalSheet`, `WalletRepository`, `WalletNotifier`, and `HomeScreen`**
  Wire `/pin/setup` and `/home` in `app_router.dart`. `WalletNotifier` fetches `GET /api/v1/wallets/me`, and `HomeScreen` supports pull-to-refresh syncing both `GET /api/v1/wallets/me` and `GET /api/v1/users/me` (`hasPin`), with a notification bell button navigating to `/notifications` (live `unreadCountProvider` badge wired in Task 8).

- [ ] **Step 4: Run tests to verify pass**
  Run: `flutter test test/core/widgets/pin_modal_sheet_test.dart test/features/pin/ test/features/home_wallet/; flutter analyze`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add 6-digit PIN setup, reusable PinModalSheet, and Home Wallet dashboard"
  ```

---

### Task 5: VNPay Top-Up & WebView Return Interception

**Files:**
- Create: `ewallet-mobile/lib/features/topup/data/topup_models.dart`
- Create: `ewallet-mobile/lib/features/topup/data/topup_repository.dart`
- Create: `ewallet-mobile/lib/features/topup/application/vnpay_uri_helper.dart`
- Create: `ewallet-mobile/lib/features/topup/application/topup_notifier.dart`
- Create: `ewallet-mobile/lib/features/topup/presentation/topup_amount_screen.dart`
- Create: `ewallet-mobile/lib/features/topup/presentation/vnpay_webview_screen.dart`
- Create: `ewallet-mobile/lib/features/topup/presentation/topup_result_screen.dart`
- Modify: `ewallet-mobile/lib/core/routing/app_router.dart`
- Test: `ewallet-mobile/test/features/topup/vnpay_uri_helper_test.dart`
- Test: `ewallet-mobile/test/features/topup/topup_notifier_test.dart`
- Test: `ewallet-mobile/test/features/topup/topup_result_screen_test.dart`

**Interfaces:**
- Consumes: `apiDioProvider`, `walletProvider` (Tasks 2, 4)
- Produces:
  - `VnpayUriHelper.isReturnUrl(String url)` (`bool`), `VnpayUriHelper.buildReturnApiPath(Uri uri)` (`String` preserving raw `uri.query`)
  - `TopupRepository`: `initiateTopup(int amount, String? description)`, `verifyReturnRaw(String rawReturnPath)`, `getTransactionStatus(int txId)`
  - `TopupNotifier` managing initiation, return verification, bounded 3-attempt transaction status polling (`1s` delay), and result state (`completed`, `pendingWebhook`, `failed`)

- [ ] **Step 1: Write the failing tests for `VnpayUriHelper`, `TopupNotifier`, and `TopupResultScreen`**
  - `vnpay_uri_helper_test.dart`:
    - `isReturnUrl_matchesLocalhostOrLanReturnPathAndIgnoresSandboxPayPage`
    - `buildReturnApiPath_preservesRawUriQueryWithPlusEncodingWithoutReencoding` (`vnp_OrderInfo=Topup+wallet+transaction+1` stays `+`, never becomes `%2B` or `%20`)
  - `topup_notifier_test.dart`:
    - `handleReturnUrl_validSignatureSuccessCodeAndCompletedTx_transitionsToCompletedAndRefreshesWallet`
    - `handleReturnUrl_validSignatureSuccessCodeWhileTxStillPendingAfter3Polls_transitionsToPendingWebhook`
    - `handleReturnUrl_invalidSignatureOrNon00Code_transitionsToFailed`
  - `topup_result_screen_test.dart`:
    - Renders `"Kiểm tra lại trạng thái"` button when status is `pendingWebhook` and calls `recheckStatus()`.

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/features/topup/`
  Expected: FAIL with missing files.

- [ ] **Step 3: Implement `VnpayUriHelper`, `TopupRepository`, `TopupNotifier`, `TopupAmountScreen`, `VnpayWebViewScreen`, and `TopupResultScreen`**
  In `VnpayWebViewScreen`'s `NavigationDelegate.onNavigationRequest`: detect `VnpayUriHelper.isReturnUrl(request.url)`, return `NavigationDecision.prevent`, invoke `topupNotifier.handleReturnUrl(Uri.parse(request.url))` (which calls `apiDio.get('/api/v1/payments/vnpay-return?${uri.query}')`), and navigate to `/topup/result`.

- [ ] **Step 4: Run tests to verify pass**
  Run: `flutter test test/features/topup/; flutter analyze`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add VNPay top-up flow, raw-query WebView interception, and status reconciliation"
  ```

---

### Task 6: Phone Lookup, P2P Transfer & Cash-Out Withdrawal (`Idempotency-Key` Lifecycle)

**Files:**
- Create: `ewallet-mobile/lib/features/transfer/data/transfer_models.dart`
- Create: `ewallet-mobile/lib/features/transfer/data/transfer_repository.dart`
- Create: `ewallet-mobile/lib/features/transfer/application/transfer_notifier.dart`
- Create: `ewallet-mobile/lib/features/transfer/presentation/transfer_screen.dart`
- Create: `ewallet-mobile/lib/features/transfer/presentation/transfer_receipt_screen.dart`
- Create: `ewallet-mobile/lib/features/withdraw/data/withdraw_models.dart`
- Create: `ewallet-mobile/lib/features/withdraw/data/withdraw_repository.dart`
- Create: `ewallet-mobile/lib/features/withdraw/application/withdraw_notifier.dart`
- Create: `ewallet-mobile/lib/features/withdraw/presentation/withdraw_screen.dart`
- Create: `ewallet-mobile/lib/features/withdraw/presentation/withdraw_receipt_screen.dart`
- Modify: `ewallet-mobile/lib/core/routing/app_router.dart`
- Test: `ewallet-mobile/test/features/transfer/transfer_notifier_test.dart`
- Test: `ewallet-mobile/test/features/withdraw/withdraw_notifier_test.dart`
- Test: `ewallet-mobile/test/features/transfer/transfer_screen_test.dart`

**Interfaces:**
- Consumes: `apiDioProvider`, `authSessionProvider`, `showPinModalSheet`, `walletProvider` (Tasks 2–4)
- Produces:
  - `QrPrefillArgs(String receiverPhone, String receiverName, int? amount, String? description, bool isDynamic)`
  - `TransferNotifier` & `WithdrawNotifier` managing `pendingIdempotencyKey` (`Uuid().v4()`), lookup (`GET /api/v1/users/lookup?phone=...`), transfer (`POST /api/v1/transfers`), and withdraw (`POST /api/v1/withdrawals`)

- [ ] **Step 1: Write the failing tests for lookup, `Idempotency-Key` rules, and pre-flight PIN routing**
  - `transfer_notifier_test.dart`:
    - `submit_preservesSameUuidV4On409Or500OrTimeout_andRegeneratesOn400InvalidPinOrInputChange`
    - `lookup_mapsIsSelfAndCanReceiveFalseAnd429RateLimit`
    - `submit_whenHasPinFalseOrBackendPinNotSet_flagsNeedsPinSetup`
  - `withdraw_notifier_test.dart`:
    - `submit_preservesUuidOn409Or5xx_andRegeneratesOn400InsufficientBalanceOrBankChange`
    - `validate_enforcesBankCodeRegexAccountNumberAnd10kTo50mAmountBounds`
  - `transfer_screen_test.dart`:
    - When `QrPrefillArgs(isDynamic: true, amount: 150000)` is passed, amount field is read-only; when `isDynamic: false`, amount field is editable.

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/features/transfer/ test/features/withdraw/`
  Expected: FAIL with missing files.

- [ ] **Step 3: Implement Transfer and Withdrawal repositories, notifiers, screens, and receipt screens**
  Ensure both notifiers keep `pendingIdempotencyKey` intact across `401`/`409`/`5xx`/network timeout, and regenerate `Uuid().v4()` immediately when form inputs change or on any other `4xx` response. Pre-flight check `authSessionProvider.hasPin == true` before opening `PinModalSheet`, redirecting to `/pin/setup` if unset or if `PIN_NOT_SET` is returned.

- [ ] **Step 4: Run tests to verify pass**
  Run: `flutter test test/features/transfer/ test/features/withdraw/; flutter analyze`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add phone lookup, P2P transfer, and bank withdrawal with UUID idempotency lifecycle"
  ```

---

### Task 7: QR Code Hub (`qr_flutter` + `mobile_scanner` + Manual Paste) & Transaction History

**Files:**
- Create: `ewallet-mobile/lib/features/qr/data/qr_models.dart`
- Create: `ewallet-mobile/lib/features/qr/data/qr_repository.dart`
- Create: `ewallet-mobile/lib/features/qr/application/qr_notifier.dart`
- Create: `ewallet-mobile/lib/features/qr/presentation/qr_hub_screen.dart`
- Create: `ewallet-mobile/lib/features/history/data/history_models.dart`
- Create: `ewallet-mobile/lib/features/history/data/history_repository.dart`
- Create: `ewallet-mobile/lib/features/history/application/history_notifier.dart`
- Create: `ewallet-mobile/lib/features/history/presentation/history_screen.dart`
- Modify: `ewallet-mobile/lib/core/routing/app_router.dart`
- Test: `ewallet-mobile/test/features/qr/qr_notifier_test.dart`
- Test: `ewallet-mobile/test/features/qr/qr_hub_screen_test.dart`
- Test: `ewallet-mobile/test/features/history/history_notifier_test.dart`

**Interfaces:**
- Consumes: `apiDioProvider`, `QrPrefillArgs`, `StatusBadge`, `CurrencyFormatter` (Tasks 1, 2, 6)
- Produces:
  - `QrRepository`: `generateQr({required String type, int? amount, String? description})` (`POST /api/v1/qr/generate`), `parseQr(String qrContent)` (`POST /api/v1/qr/parse`)
  - `HistoryRepository`: `getHistory({required int page, int size = 20, TransactionType? type, TransactionStatus? status, String direction = 'ALL', DateTime? from, DateTime? to})`, `getTransactionDetail(int id)`
  - `historyNotifierProvider` (exposed so SSE in Task 8 can invalidate)

- [ ] **Step 1: Write the failing tests for QR generate/parse/manual paste and paginated History**
  - `qr_notifier_test.dart`:
    - `generate_dynamicValidates1kTo50mAndDescription255_andReturnsQrContent`
    - `parse_rejectsIsSelfOrCanTransferFalse_andMapsExpiredQrCode`
  - `qr_hub_screen_test.dart`:
    - `manualPasteButton_parsesUriAndRoutesToTransferWithQrPrefillArgs`
  - `history_notifier_test.dart`:
    - `loadNextPage_appendsPageItemsAndStopsAtLastPage`
    - `applyFilters_resetsPageTo0AndPassesIso8601FromToAndDirection`

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/features/qr/ test/features/history/`
  Expected: FAIL with missing files.

- [ ] **Step 3: Implement QR & History modules and wire `/qr` + `/history` in `app_router.dart`**
  In `QrHubScreen`, Tab 1 renders `QrImageView` (`qr_flutter`) + `STATIC`/`DYNAMIC` toggle + expiry countdown + Copy button; Tab 2 provides `MobileScanner` (paused on detection) + a `"Dán mã QR"` sheet taking `vwallet://transfer?...` strings directly.

- [ ] **Step 4: Run tests to verify pass**
  Run: `flutter test test/features/qr/ test/features/history/; flutter analyze`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add QR generate/scan/manual-paste hub and paginated transaction history"
  ```

---

### Task 8: UTF-8 Chunk-Safe SSE Client, App Lifecycle Binding & Notifications Inbox

**Files:**
- Create: `ewallet-mobile/lib/core/realtime/sse_parser.dart`
- Create: `ewallet-mobile/lib/core/realtime/sse_notification_service.dart`
- Create: `ewallet-mobile/lib/core/realtime/app_lifecycle_observer.dart`
- Create: `ewallet-mobile/lib/features/notifications/data/notification_models.dart`
- Create: `ewallet-mobile/lib/features/notifications/data/notification_repository.dart`
- Create: `ewallet-mobile/lib/features/notifications/application/notifications_notifier.dart`
- Create: `ewallet-mobile/lib/features/notifications/presentation/notifications_screen.dart`
- Modify: `ewallet-mobile/lib/app.dart`
- Modify: `ewallet-mobile/lib/core/routing/app_router.dart`
- Modify: `ewallet-mobile/lib/features/home_wallet/presentation/home_screen.dart`
- Test: `ewallet-mobile/test/core/realtime/sse_parser_test.dart`
- Test: `ewallet-mobile/test/core/realtime/sse_notification_service_test.dart`
- Test: `ewallet-mobile/test/features/notifications/notifications_notifier_test.dart`

**Interfaces:**
- Consumes: `sseDioProvider`, `TokenStorage`, `TokenRefreshCoordinator`, `authSessionProvider`, `walletProvider`, `historyNotifierProvider` (Tasks 2, 3, 4, 7)
- Produces:
  - `SseFrame(String? id, String? event, String data)`
  - `SseParser.parse(Stream<List<int>> byteStream)` (`Stream<SseFrame>`)
  - `SseNotificationService`: `connect()`, `disconnect()`, `Stream<NotificationResponse> get notifications`
  - `unreadCountProvider`, `notificationsNotifierProvider`

- [ ] **Step 1: Write the failing tests for UTF-8 chunked `SseParser`, `SseNotificationService` lifecycle/401, and `NotificationsNotifier`**
  - `sse_parser_test.dart`:
    - `parse_multiByteVietnameseUtf8SplitAcrossChunks_emitsIntactNotificationFrame` (splits UTF-8 bytes of `"Bạn đã nhận 100,000 VND"` mid-codepoint across two `List<int>` chunks and verifies exact decoded Vietnamese text)
    - `parse_ignoresColonCommentsAndHandlesMultiLineData`
  - `sse_notification_service_test.dart`:
    - `onAppPaused_cancelsActiveStreamImmediately`
    - `on401Handshake_usesTokenRefreshCoordinatorAndReconnectsWithNewToken`
  - `notifications_notifier_test.dart`:
    - `markAsRead_updatesItemLocallyAndDecrementsUnreadCount`
    - `markAllAsRead_marksAllReadAndResetsUnreadCountToZero`
    - `onIncomingSseNotification_prependsDeduplicatedItemAndInvalidatesWalletAndHistory`

- [ ] **Step 2: Run tests to verify they fail**
  Run: `flutter test test/core/realtime/ test/features/notifications/`
  Expected: FAIL with missing files.

- [ ] **Step 3: Implement `SseParser`, `SseNotificationService`, `AppLifecycleObserver`, `NotificationsNotifier`, and `NotificationsScreen`**
  - `SseParser` strictly uses `byteStream.transform(utf8.decoder).transform(const LineSplitter())` before assembling `id:` / `event:` / `data:` lines.
  - `AppLifecycleObserver` hooks into `app.dart` (`WidgetsBindingObserver`), disconnecting SSE on `paused`/`detached` and reconnecting + refreshing `unreadCountProvider` & `walletProvider` on `resumed`.
  - Incoming `event: notification` frames invalidate `unreadCountProvider`, `walletProvider`, and `historyNotifierProvider`, and show a tappable in-app banner.
  - Wire `/notifications` route, bind `unreadCountProvider` badge on `HomeScreen`, and connect `logout()` cleanup callback to `sseNotificationService.disconnect()`.

- [ ] **Step 4: Run the full `flutter test` suite and `flutter analyze`**
  Run: `flutter test; flutter analyze` (from `ewallet-mobile/`)
  Expected: All unit & widget tests PASS, 0 analyzer errors/warnings.

- [ ] **Step 5: Commit**
  ```powershell
  git add ewallet-mobile/; git commit -m "feat(mobile): add UTF-8 chunk-safe SSE realtime client, lifecycle control, and notifications inbox"
  ```

---

## Final Real-Device Verification Checklist
Before opening the Phase D PR:
1. Run `adb reverse tcp:8080 tcp:8080` and verify Docker (`vwallet-mysql`, `vwallet-redis`, `vwallet-kafka`) + `ewallet-backend` are running.
2. Run `flutter run` on connected physical Android device and verify end-to-end: Register -> Setup 6-digit PIN -> VNPay Top-Up WebView return + IPN webhook -> Live SSE + Home balance update -> Dynamic QR generate & paste/scan -> P2P Transfer -> Cash-Out Withdrawal -> History filters -> Best-effort Logout & Single-Device RTR expiry handling.
