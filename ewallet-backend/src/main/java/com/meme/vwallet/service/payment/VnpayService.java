package com.meme.vwallet.service.payment;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Getter
@Setter
public class VnpayService {

    private static final String HMAC_SHA512 = "HmacSHA512";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Value("${vnpay.tmn-code:VWALLET1}")
    private String tmnCode;

    @Value("${vnpay.hash-secret:SECRETKEY1234567890123456789012}")
    private String hashSecret;

    @Value("${vnpay.pay-url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String payUrl;

    @Value("${vnpay.return-url:http://localhost:8080/api/v1/payments/vnpay-return}")
    private String returnUrl;

    public VnpayService() {
        this.tmnCode = "VWALLET1";
        this.hashSecret = "SECRETKEY1234567890123456789012";
        this.payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
        this.returnUrl = "http://localhost:8080/api/v1/payments/vnpay-return";
    }

    public VnpayService(String tmnCode, String hashSecret, String payUrl, String returnUrl) {
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
    }

    /**
     * Builds payment URL for VNPay gateway with alphabetically sorted query parameters and HMAC-SHA512 signature.
     */
    public String createPaymentUrl(Long transactionId, Long amountVnd, String orderInfo, String ipAddress) {
        long vnpAmount = amountVnd * 100L;
        String clientIp = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress.split(",")[0].trim() : "127.0.0.1";
        String info = (orderInfo != null && !orderInfo.isBlank()) ? orderInfo : "Topup transaction " + transactionId;
        String createDate = DATE_FORMATTER.format(Instant.now());

        Map<String, String> vnpParams = new HashMap<>();
        vnpParams.put("vnp_Version", "2.1.0");
        vnpParams.put("vnp_Command", "pay");
        vnpParams.put("vnp_TmnCode", tmnCode);
        vnpParams.put("vnp_Amount", String.valueOf(vnpAmount));
        vnpParams.put("vnp_CurrCode", "VND");
        vnpParams.put("vnp_TxnRef", String.valueOf(transactionId));
        vnpParams.put("vnp_OrderInfo", info);
        vnpParams.put("vnp_OrderType", "other");
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_ReturnUrl", returnUrl);
        vnpParams.put("vnp_IpAddr", clientIp);
        vnpParams.put("vnp_CreateDate", createDate);

        List<String> fieldNames = new ArrayList<>(vnpParams.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();

        for (String fieldName : fieldNames) {
            String fieldValue = vnpParams.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                String encodedKey = URLEncoder.encode(fieldName, StandardCharsets.UTF_8);
                String encodedValue = URLEncoder.encode(fieldValue, StandardCharsets.UTF_8);
                if (hashData.length() > 0) {
                    hashData.append('&');
                    query.append('&');
                }
                hashData.append(fieldName).append('=').append(encodedValue);
                query.append(encodedKey).append('=').append(encodedValue);
            }
        }

        String secureHash = hmacSHA512(hashSecret, hashData.toString());
        return payUrl + "?" + query + "&vnp_SecureHash=" + secureHash;
    }

    /**
     * Verifies the HMAC-SHA512 signature in incoming webhook / return callback using configured hash secret.
     */
    public boolean verifySignature(Map<String, String> params) {
        return verifySignature(params, this.hashSecret);
    }

    /**
     * Verifies the HMAC-SHA512 signature in incoming webhook / return callback with specified secret key.
     */
    public boolean verifySignature(Map<String, String> params, String secretKey) {
        if (params == null || secretKey == null || secretKey.isBlank()) {
            return false;
        }

        String vnpSecureHash = params.get("vnp_SecureHash");
        if (vnpSecureHash == null || vnpSecureHash.isBlank()) {
            vnpSecureHash = params.get("vnp_securehash");
        }
        if (vnpSecureHash == null || vnpSecureHash.isBlank()) {
            return false;
        }

        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        for (String fieldName : fieldNames) {
            if ("vnp_SecureHash".equalsIgnoreCase(fieldName) || "vnp_SecureHashType".equalsIgnoreCase(fieldName)) {
                continue;
            }
            String fieldValue = params.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                String encodedValue = URLEncoder.encode(fieldValue, StandardCharsets.UTF_8);
                if (hashData.length() > 0) {
                    hashData.append('&');
                }
                hashData.append(fieldName).append('=').append(encodedValue);
            }
        }

        String calculatedHash = hmacSHA512(secretKey, hashData.toString());
        byte[] calculated = calculatedHash.toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] received = vnpSecureHash.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(calculated, received);
    }

    /**
     * Computes HMAC-SHA512 hash and formats as hex string.
     */
    public String hmacSHA512(String key, String data) {
        if (key == null || data == null) {
            throw new IllegalArgumentException("Key and data cannot be null for HMAC-SHA512 computation");
        }
        try {
            Mac hmac512 = Mac.getInstance(HMAC_SHA512);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA512);
            hmac512.init(secretKeySpec);
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (Exception ex) {
            throw new RuntimeException("Error computing HMAC-SHA512", ex);
        }
    }
}
