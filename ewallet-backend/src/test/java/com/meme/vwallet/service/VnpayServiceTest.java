package com.meme.vwallet.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VnpayServiceTest {

    private VnpayService vnpayService;

    private static final String TEST_TMN_CODE = "VWALLET1";
    private static final String TEST_HASH_SECRET = "SECRETKEY1234567890123456789012";
    private static final String TEST_PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static final String TEST_RETURN_URL = "http://localhost:8080/api/v1/payments/vnpay-return";

    @BeforeEach
    void setUp() {
        vnpayService = new VnpayService(TEST_TMN_CODE, TEST_HASH_SECRET, TEST_PAY_URL, TEST_RETURN_URL);
    }

    @Nested
    @DisplayName("HMAC-SHA512 Hash Generation")
    class HmacSha512Tests {

        @Test
        @DisplayName("Should generate correct HMAC-SHA512 hex digest")
        void shouldGenerateCorrectHmacSha512Hex() {
            String key = "testSecret";
            String data = "Hello VNPay HMAC-SHA512";

            String hash = vnpayService.hmacSHA512(key, data);

            assertThat(hash).isNotNull().hasSize(128); // SHA-512 hex is 128 characters
            // Deterministic hash check
            String hash2 = vnpayService.hmacSHA512(key, data);
            assertThat(hash).isEqualTo(hash2);
        }

        @Test
        @DisplayName("Should throw exception when key or data is null")
        void shouldThrowWhenKeyOrDataIsNull() {
            assertThatThrownBy(() -> vnpayService.hmacSHA512(null, "data"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> vnpayService.hmacSHA512("key", null))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Payment URL Creation")
    class CreatePaymentUrlTests {

        @Test
        @DisplayName("Should build payment URL with sorted parameters and vnp_Amount multiplied by 100")
        void shouldBuildPaymentUrlWithSortedParamsAndMultipliedAmount() {
            Long transactionId = 2002L;
            Long amountVnd = 500000L;
            String orderInfo = "Topup wallet transaction 2002";
            String ipAddress = "192.168.1.100";

            String paymentUrl = vnpayService.createPaymentUrl(transactionId, amountVnd, orderInfo, ipAddress);

            assertThat(paymentUrl).isNotNull().startsWith(TEST_PAY_URL + "?");

            // Extract query string
            URI uri = URI.create(paymentUrl);
            String rawQuery = uri.getRawQuery();
            assertThat(rawQuery).isNotNull();

            String[] pairs = rawQuery.split("&");
            List<String> paramKeys = new ArrayList<>();
            Map<String, String> params = new HashMap<>();

            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                String key = pair.substring(0, idx);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                paramKeys.add(key);
                params.put(key, val);
            }

            // Check vnp_Amount is multiplied by 100
            assertThat(params.get("vnp_Amount")).isEqualTo("50000000");
            assertThat(params.get("vnp_TxnRef")).isEqualTo("2002");
            assertThat(params.get("vnp_TmnCode")).isEqualTo(TEST_TMN_CODE);
            assertThat(params.get("vnp_Command")).isEqualTo("pay");
            assertThat(params.get("vnp_CurrCode")).isEqualTo("VND");
            assertThat(params.get("vnp_ReturnUrl")).isEqualTo(TEST_RETURN_URL);
            assertThat(params.get("vnp_IpAddr")).isEqualTo("192.168.1.100");
            assertThat(params.get("vnp_SecureHash")).isNotNull().hasSize(128);

            // Verify alphabetical sort order (excluding vnp_SecureHash appended at end)
            List<String> sortedKeysWithoutHash = paramKeys.stream()
                    .filter(k -> !k.equals("vnp_SecureHash"))
                    .toList();
            assertThat(sortedKeysWithoutHash).isSorted();

            // Self-verify signature of generated payment URL
            boolean valid = vnpayService.verifySignature(params);
            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("Should fallback to default IP address when ip is null or blank")
        void shouldFallbackToDefaultIpWhenNull() {
            String url = vnpayService.createPaymentUrl(123L, 10000L, "Order info", null);
            assertThat(url).contains("vnp_IpAddr=127.0.0.1");
        }
    }

    @Nested
    @DisplayName("Signature Verification")
    class VerifySignatureTests {

        @Test
        @DisplayName("Should return true for valid parameters and signature")
        void shouldReturnTrueForValidSignature() {
            Map<String, String> params = new HashMap<>();
            params.put("vnp_Amount", "10000000");
            params.put("vnp_Command", "pay");
            params.put("vnp_CurrCode", "VND");
            params.put("vnp_ResponseCode", "00");
            params.put("vnp_TmnCode", TEST_TMN_CODE);
            params.put("vnp_TxnRef", "1001");

            // Calculate expected hash
            // Sorted: vnp_Amount=10000000&vnp_Command=pay&vnp_CurrCode=VND&vnp_ResponseCode=00&vnp_TmnCode=VWALLET1&vnp_TxnRef=1001
            String hashData = "vnp_Amount=10000000&vnp_Command=pay&vnp_CurrCode=VND&vnp_ResponseCode=00&vnp_TmnCode="
                    + TEST_TMN_CODE + "&vnp_TxnRef=1001";
            String secureHash = vnpayService.hmacSHA512(TEST_HASH_SECRET, hashData);
            params.put("vnp_SecureHash", secureHash);

            boolean result = vnpayService.verifySignature(params, TEST_HASH_SECRET);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false when amount is tampered")
        void shouldReturnFalseWhenAmountTampered() {
            Map<String, String> params = new HashMap<>();
            params.put("vnp_Amount", "10000000");
            params.put("vnp_TxnRef", "1001");
            String hashData = "vnp_Amount=10000000&vnp_TxnRef=1001";
            String secureHash = vnpayService.hmacSHA512(TEST_HASH_SECRET, hashData);
            params.put("vnp_SecureHash", secureHash);

            // Attacker modifies vnp_Amount
            params.put("vnp_Amount", "50000000");

            boolean result = vnpayService.verifySignature(params, TEST_HASH_SECRET);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false when vnp_SecureHash is missing or blank")
        void shouldReturnFalseWhenSecureHashMissing() {
            Map<String, String> params = new HashMap<>();
            params.put("vnp_Amount", "10000000");
            params.put("vnp_TxnRef", "1001");

            boolean result = vnpayService.verifySignature(params, TEST_HASH_SECRET);
            assertThat(result).isFalse();

            params.put("vnp_SecureHash", "");
            assertThat(vnpayService.verifySignature(params, TEST_HASH_SECRET)).isFalse();
        }

        @Test
        @DisplayName("Should ignore vnp_SecureHashType during hash calculation")
        void shouldIgnoreSecureHashType() {
            Map<String, String> params = new HashMap<>();
            params.put("vnp_Amount", "10000000");
            params.put("vnp_TxnRef", "1001");
            String hashData = "vnp_Amount=10000000&vnp_TxnRef=1001";
            String secureHash = vnpayService.hmacSHA512(TEST_HASH_SECRET, hashData);

            params.put("vnp_SecureHash", secureHash);
            params.put("vnp_SecureHashType", "HmacSHA512");

            boolean result = vnpayService.verifySignature(params, TEST_HASH_SECRET);
            assertThat(result).isTrue();
        }
    }
}
