package com.meme.vwallet.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meme.vwallet.dto.request.QrGenerateRequest;
import com.meme.vwallet.dto.request.QrParseRequest;
import com.meme.vwallet.dto.response.QrGenerateResponse;
import com.meme.vwallet.dto.response.QrParseResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.entity.Wallet;
import com.meme.vwallet.entity.enums.WalletStatus;
import com.meme.vwallet.exception.ExpiredQrCodeException;
import com.meme.vwallet.exception.InvalidQrCodeException;
import com.meme.vwallet.exception.UserLockedException;
import com.meme.vwallet.exception.UserNotFoundException;
import com.meme.vwallet.exception.WalletException;
import com.meme.vwallet.repository.UserRepository;
import com.meme.vwallet.repository.WalletRepository;
import com.meme.vwallet.util.PhoneNumberUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Service for generating and verifying HMAC-SHA256 signed static and dynamic transfer QR codes.
 */
@Service
public class QrCodeService {

    private static final String QR_SCHEME = "vwallet";
    private static final String QR_HOST = "transfer";
    private static final String QR_PREFIX = "vwallet://transfer?";
    private static final int PAYLOAD_VERSION = 1;
    private static final String TYPE_STATIC = "STATIC";
    private static final String TYPE_DYNAMIC = "DYNAMIC";
    private static final long SYSTEM_USER_ID = 1L;
    private static final String SYSTEM_USER_PHONE = "0000000000";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final ObjectMapper objectMapper;
    private final String qrSecret;
    private final long dynamicTtlSeconds;
    private final long minAmount;
    private final long maxAmount;

    @Autowired
    public QrCodeService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            ObjectMapper objectMapper,
            @Value("${vwallet.qr.secret:dGhpc19pc19hX3Zlcnlfc2VjdXJlXzI1Nl9iaXRfcXJfZGV2X3NlY3JldF9rZXkh}") String qrSecret,
            @Value("${vwallet.qr.dynamic-ttl-seconds:900}") long dynamicTtlSeconds,
            @Value("${vwallet.qr.min-amount:1000}") long minAmount,
            @Value("${vwallet.qr.max-amount:50000000}") long maxAmount
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.objectMapper = objectMapper;
        this.qrSecret = qrSecret;
        this.dynamicTtlSeconds = dynamicTtlSeconds;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    /**
     * Generates a signed static or dynamic transfer QR payload for an active user and wallet.
     */
    @Transactional(readOnly = true)
    public QrGenerateResponse generateQr(Long userId, QrGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Yêu cầu tạo mã QR không hợp lệ");
        }

        String type = request.getType();
        if (!TYPE_STATIC.equals(type) && !TYPE_DYNAMIC.equals(type)) {
            throw new IllegalArgumentException("Loại mã QR phải là STATIC hoặc DYNAMIC");
        }

        String rawDescription = request.getDescription();
        if (rawDescription != null && rawDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Nội dung chuyển tiền tối đa 255 ký tự");
        }
        String normalizedDescription = (rawDescription != null && !rawDescription.isBlank())
                ? rawDescription.trim()
                : null;

        Long effectiveAmount = null;
        Long expEpoch = null;
        Instant expiresAt = null;
        if (TYPE_DYNAMIC.equals(type)) {
            Long amount = request.getAmount();
            if (amount == null || amount < minAmount || amount > maxAmount) {
                throw new IllegalArgumentException(
                        "Số tiền QR động phải nằm trong khoảng từ " + minAmount + " đến " + maxAmount + " VND"
                );
            }
            effectiveAmount = amount;
            expEpoch = Instant.now().plusSeconds(dynamicTtlSeconds).getEpochSecond();
            expiresAt = Instant.ofEpochSecond(expEpoch);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy người dùng: " + userId));
        if (Long.valueOf(SYSTEM_USER_ID).equals(user.getId())) {
            throw new UserNotFoundException("Không tìm thấy người dùng: " + userId);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new UserLockedException();
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletException("Không tìm thấy ví của người dùng"));
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Ví người dùng không ở trạng thái hoạt động: " + wallet.getStatus());
        }

        QrPayload payload = QrPayload.builder()
                .v(PAYLOAD_VERSION)
                .type(type)
                .phone(user.getPhoneNumber())
                .amount(effectiveAmount)
                .desc(normalizedDescription)
                .exp(expEpoch)
                .build();

        String encodedPayload = encodePayload(payload);
        String signature = hmacSha256Hex(qrSecret, encodedPayload);
        String qrContent = QR_PREFIX + "d=" + encodedPayload + "&sig=" + signature;

        return QrGenerateResponse.builder()
                .qrContent(qrContent)
                .type(type)
                .receiverPhone(user.getPhoneNumber())
                .receiverName(user.getFullName())
                .amount(effectiveAmount)
                .description(normalizedDescription)
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Verifies QR structure, HMAC-SHA256 signature, expiration, and live recipient status.
     */
    @Transactional(readOnly = true)
    public QrParseResponse parseQr(Long currentUserId, QrParseRequest request) {
        if (request == null || request.getQrContent() == null || request.getQrContent().isBlank()) {
            throw new InvalidQrCodeException("Mã QR không hợp lệ");
        }

        Map<String, String> queryParams = extractAndValidateUriParams(request.getQrContent().trim());
        String encodedPayload = queryParams.get("d");
        String receivedSig = queryParams.get("sig");

        String expectedSig = hmacSha256Hex(qrSecret, encodedPayload);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                receivedSig.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new InvalidQrCodeException("Chữ ký mã QR không hợp lệ");
        }

        QrPayload payload = decodePayload(encodedPayload);
        validateDecodedPayload(payload);

        Instant expiresAt = null;
        if (TYPE_DYNAMIC.equals(payload.getType())) {
            if (Instant.now().getEpochSecond() > payload.getExp()) {
                throw new ExpiredQrCodeException("Mã QR đã hết hạn");
            }
            expiresAt = Instant.ofEpochSecond(payload.getExp());
        }

        String rawPhone = payload.getPhone().trim();
        if (SYSTEM_USER_PHONE.equals(rawPhone)) {
            throw new UserNotFoundException("Không tìm thấy người dùng với số điện thoại: " + rawPhone);
        }

        String normalizedPhone;
        try {
            normalizedPhone = PhoneNumberUtils.normalize(rawPhone);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQrCodeException("Số điện thoại trong mã QR không hợp lệ");
        }

        User targetUser = userRepository.findByPhoneNumber(normalizedPhone)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy người dùng với số điện thoại: " + normalizedPhone));
        if (Long.valueOf(SYSTEM_USER_ID).equals(targetUser.getId())) {
            throw new UserNotFoundException("Không tìm thấy người dùng với số điện thoại: " + normalizedPhone);
        }

        Wallet targetWallet = walletRepository.findByUserId(targetUser.getId())
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy ví của người dùng: " + normalizedPhone));

        boolean isSelf = Objects.equals(targetUser.getId(), currentUserId);
        boolean canTransfer = !isSelf
                && "ACTIVE".equals(targetUser.getStatus())
                && targetWallet.getStatus() == WalletStatus.ACTIVE;

        return QrParseResponse.builder()
                .type(payload.getType())
                .receiverPhone(targetUser.getPhoneNumber())
                .receiverName(targetUser.getFullName())
                .amount(payload.getAmount())
                .description(payload.getDesc())
                .isSelf(isSelf)
                .canTransfer(canTransfer)
                .expiresAt(expiresAt)
                .build();
    }

    private Map<String, String> extractAndValidateUriParams(String rawUri) {
        URI uri;
        try {
            uri = URI.create(rawUri);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQrCodeException("Định dạng mã QR không hợp lệ");
        }

        String path = uri.getPath();
        if (!QR_SCHEME.equals(uri.getScheme())
                || !QR_HOST.equals(uri.getHost())
                || (path != null && !path.isEmpty())) {
            throw new InvalidQrCodeException("Mã QR không thuộc hệ thống vWallet");
        }

        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new InvalidQrCodeException("Thiếu dữ liệu trong mã QR");
        }

        Map<String, String> params = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank() || params.containsKey(kv[0])) {
                throw new InvalidQrCodeException("Tham số mã QR không hợp lệ");
            }
            params.put(kv[0], kv[1]);
        }

        if (!params.containsKey("d") || !params.containsKey("sig")) {
            throw new InvalidQrCodeException("Mã QR thiếu payload hoặc chữ ký");
        }
        if (params.size() != 2) {
            throw new InvalidQrCodeException("Mã QR chứa tham số không hợp lệ");
        }
        return params;
    }

    private String encodePayload(QrPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể mã hóa payload QR", ex);
        }
    }

    private QrPayload decodePayload(String encodedPayload) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedPayload);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, QrPayload.class);
        } catch (Exception ex) {
            throw new InvalidQrCodeException("Không thể giải mã nội dung mã QR");
        }
    }

    private void validateDecodedPayload(QrPayload payload) {
        if (payload == null
                || !Integer.valueOf(PAYLOAD_VERSION).equals(payload.getV())
                || payload.getPhone() == null
                || payload.getPhone().isBlank()) {
            throw new InvalidQrCodeException("Dữ liệu mã QR không hợp lệ");
        }

        String type = payload.getType();
        if (TYPE_STATIC.equals(type)) {
            if (payload.getAmount() != null || payload.getExp() != null) {
                throw new InvalidQrCodeException("Mã QR tĩnh không hợp lệ");
            }
        } else if (TYPE_DYNAMIC.equals(type)) {
            if (payload.getAmount() == null || payload.getAmount() <= 0 || payload.getExp() == null) {
                throw new InvalidQrCodeException("Mã QR động thiếu số tiền hoặc thời hạn");
            }
        } else {
            throw new InvalidQrCodeException("Loại mã QR không được hỗ trợ");
        }
    }

    private String hmacSha256Hex(String secret, String data) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("QR HMAC secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể ký HMAC-SHA256 cho mã QR", ex);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class QrPayload {
        private Integer v;
        private String type;
        private String phone;
        private Long amount;
        private String desc;
        private Long exp;
    }
}
