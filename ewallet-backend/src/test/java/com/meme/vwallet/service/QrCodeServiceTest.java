package com.meme.vwallet.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QrCodeServiceTest {

    private static final String TEST_SECRET = "test_qr_secret_key_for_unit_tests_256bit!";
    private static final long TTL_SECONDS = 900L;
    private static final long MIN_AMOUNT = 1_000L;
    private static final long MAX_AMOUNT = 50_000_000L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    private ObjectMapper objectMapper;
    private QrCodeService qrCodeService;

    private User userA;
    private Wallet walletA;
    private User userB;
    private Wallet walletB;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        qrCodeService = new QrCodeService(
                userRepository,
                walletRepository,
                objectMapper,
                TEST_SECRET,
                TTL_SECONDS,
                MIN_AMOUNT,
                MAX_AMOUNT
        );

        userA = User.builder()
                .id(2L)
                .phoneNumber("0901234567")
                .fullName("Nguyen Van A")
                .status("ACTIVE")
                .build();
        walletA = Wallet.builder()
                .id(2L)
                .user(userA)
                .balance(1_000_000L)
                .status(WalletStatus.ACTIVE)
                .build();

        userB = User.builder()
                .id(3L)
                .phoneNumber("0987654321")
                .fullName("Tran Thi B")
                .status("ACTIVE")
                .build();
        walletB = Wallet.builder()
                .id(3L)
                .user(userB)
                .balance(500_000L)
                .status(WalletStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("generate STATIC QR uses Base64URL without '=' padding, 64-char lowercase hex HMAC-SHA256, and null amount/exp")
    void generate_staticQr_usesBase64UrlWithoutPaddingAndNullAmountExp() throws Exception {
        when(userRepository.findById(2L)).thenReturn(Optional.of(userA));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(walletA));

        QrGenerateRequest request = QrGenerateRequest.builder()
                .type("STATIC")
                .description("My static QR")
                .build();

        QrGenerateResponse response = qrCodeService.generateQr(2L, request);

        assertThat(response.getQrContent()).startsWith("vwallet://transfer?d=");
        assertThat(response.getType()).isEqualTo("STATIC");
        assertThat(response.getReceiverPhone()).isEqualTo("0901234567");
        assertThat(response.getReceiverName()).isEqualTo("Nguyen Van A");
        assertThat(response.getAmount()).isNull();
        assertThat(response.getExpiresAt()).isNull();
        assertThat(response.getDescription()).isEqualTo("My static QR");

        URI uri = URI.create(response.getQrContent());
        String[] parts = uri.getRawQuery().split("&");
        assertThat(parts).hasSize(2);
        String d = parts[0].substring(2);
        String sig = parts[1].substring(4);

        assertThat(d).doesNotContain("=");
        assertThat(sig).matches("^[0-9a-f]{64}$");
        assertThat(sig).isEqualTo(sign(d));

        String json = new String(Base64.getUrlDecoder().decode(d), StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.get("type").asString()).isEqualTo("STATIC");
        assertThat(node.get("phone").asString()).isEqualTo("0901234567");
        assertThat(node.get("desc").asString()).isEqualTo("My static QR");
        assertThat(node.hasNonNull("amount")).isFalse();
        assertThat(node.hasNonNull("exp")).isFalse();
    }

    @Test
    @DisplayName("generate DYNAMIC QR sets amount and exp and verifies cleanly via parseQr")
    void generate_dynamicQr_setsAmountAndExpAndVerifiesViaParse() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(userA));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(walletA));
        when(userRepository.findByPhoneNumber("0901234567")).thenReturn(Optional.of(userA));

        QrGenerateRequest request = QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(150_000L)
                .description("Tien ca phe")
                .build();

        Instant before = Instant.now();
        QrGenerateResponse generated = qrCodeService.generateQr(2L, request);

        assertThat(generated.getType()).isEqualTo("DYNAMIC");
        assertThat(generated.getAmount()).isEqualTo(150_000L);
        assertThat(generated.getDescription()).isEqualTo("Tien ca phe");
        assertThat(generated.getExpiresAt()).isNotNull();
        assertThat(generated.getExpiresAt().getEpochSecond())
                .isBetween(before.getEpochSecond() + TTL_SECONDS - 5, before.getEpochSecond() + TTL_SECONDS + 5);

        QrParseResponse parsedByB = qrCodeService.parseQr(3L, new QrParseRequest(generated.getQrContent()));
        assertThat(parsedByB.getType()).isEqualTo("DYNAMIC");
        assertThat(parsedByB.getReceiverPhone()).isEqualTo("0901234567");
        assertThat(parsedByB.getReceiverName()).isEqualTo("Nguyen Van A");
        assertThat(parsedByB.getAmount()).isEqualTo(150_000L);
        assertThat(parsedByB.getDescription()).isEqualTo("Tien ca phe");
        assertThat(parsedByB.isSelf()).isFalse();
        assertThat(parsedByB.isCanTransfer()).isTrue();
        assertThat(parsedByB.getExpiresAt()).isEqualTo(generated.getExpiresAt());
    }

    @Test
    @DisplayName("generate QR for locked user throws UserLockedException and inactive wallet throws WalletException")
    void generate_lockedUserOrInactiveWallet_throwsUserLockedOrWalletException() {
        userA.setStatus("LOCKED");
        when(userRepository.findById(2L)).thenReturn(Optional.of(userA));

        QrGenerateRequest validStatic = QrGenerateRequest.builder().type("STATIC").build();
        assertThatThrownBy(() -> qrCodeService.generateQr(2L, validStatic))
                .isInstanceOf(UserLockedException.class);
        verify(walletRepository, never()).findByUserId(anyLong());

        userA.setStatus("ACTIVE");
        walletA.setStatus(WalletStatus.LOCKED);
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(walletA));

        assertThatThrownBy(() -> qrCodeService.generateQr(2L, validStatic))
                .isInstanceOf(WalletException.class);
    }

    @Test
    @DisplayName("generate DYNAMIC QR with out-of-bounds amount or description > 255 chars throws IllegalArgumentException")
    void generate_dynamicAmountOutOfBoundsOrDescriptionTooLong_throwsIllegalArgument() {
        assertThatThrownBy(() -> qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(null)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(999L)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(50_000_001L)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(10_000L)
                .description("a".repeat(256))
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("generate STATIC forces amount=null and exp=null; invalid type throws IllegalArgumentException")
    void generate_staticWithAmountOrInvalidType_forcesNullOrThrowsIllegalArgument() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(userA));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(walletA));

        QrGenerateResponse staticRes = qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("STATIC")
                .amount(500_000L)
                .build());
        assertThat(staticRes.getAmount()).isNull();
        assertThat(staticRes.getExpiresAt()).isNull();

        assertThatThrownBy(() -> qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("INVALID_TYPE")
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type(null)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse tampered payload, invalid signature, uppercase sig, or bad URI throws InvalidQrCodeException")
    void parse_tamperedPayloadOrInvalidSignature_throwsInvalidQrCode400() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(userA));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(walletA));

        String validQr = qrCodeService.generateQr(2L, QrGenerateRequest.builder()
                .type("DYNAMIC")
                .amount(50_000L)
                .build()).getQrContent();

        String[] queryParts = URI.create(validQr).getRawQuery().split("&");
        String d = queryParts[0].substring(2);
        String sig = queryParts[1].substring(4);

        char flipped = d.charAt(0) == 'a' ? 'b' : 'a';
        String tamperedPayloadQr = "vwallet://transfer?d=" + flipped + d.substring(1) + "&sig=" + sig;
        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest(tamperedPayloadQr)))
                .isInstanceOf(InvalidQrCodeException.class);

        char flippedSig = sig.charAt(0) == '0' ? '1' : '0';
        String tamperedSigQr = "vwallet://transfer?d=" + d + "&sig=" + flippedSig + sig.substring(1);
        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest(tamperedSigQr)))
                .isInstanceOf(InvalidQrCodeException.class);

        String uppercaseSigQr = "vwallet://transfer?d=" + d + "&sig=" + sig.toUpperCase();
        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest(uppercaseSigQr)))
                .isInstanceOf(InvalidQrCodeException.class);

        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest("https://evil.com/transfer?d=" + d + "&sig=" + sig)))
                .isInstanceOf(InvalidQrCodeException.class);

        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest("vwallet://transfer?d=" + d)))
                .isInstanceOf(InvalidQrCodeException.class);
    }

    @Test
    @DisplayName("parse expired DYNAMIC QR throws ExpiredQrCodeException")
    void parse_expiredDynamicQr_throwsExpiredQrCode400() throws Exception {
        long expiredEpoch = Instant.now().minusSeconds(60).getEpochSecond();
        String json = objectMapper.writeValueAsString(Map.of(
                "v", 1,
                "type", "DYNAMIC",
                "phone", "0901234567",
                "amount", 100_000L,
                "desc", "Expired order",
                "exp", expiredEpoch
        ));
        String d = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String qrContent = "vwallet://transfer?d=" + d + "&sig=" + sign(d);

        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest(qrContent)))
                .isInstanceOf(ExpiredQrCodeException.class);
    }

    @Test
    @DisplayName("parse QR for SYSTEM_USER phone 0000000000 or DB id=1 throws UserNotFoundException (404)")
    void parse_systemUserPhoneOrId1_throwsUserNotFound404() throws Exception {
        String sysPhoneJson = objectMapper.writeValueAsString(Map.of(
                "v", 1,
                "type", "STATIC",
                "phone", "0000000000"
        ));
        String dSysPhone = Base64.getUrlEncoder().withoutPadding().encodeToString(sysPhoneJson.getBytes(StandardCharsets.UTF_8));
        String qrSysPhone = "vwallet://transfer?d=" + dSysPhone + "&sig=" + sign(dSysPhone);

        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest(qrSysPhone)))
                .isInstanceOf(UserNotFoundException.class);
        verify(walletRepository, never()).findByUserId(anyLong());

        User id1User = User.builder()
                .id(1L)
                .phoneNumber("0909999999")
                .fullName("SYSTEM_USER")
                .status("ACTIVE")
                .build();
        when(userRepository.findByPhoneNumber("0909999999")).thenReturn(Optional.of(id1User));

        String sysIdJson = objectMapper.writeValueAsString(Map.of(
                "v", 1,
                "type", "STATIC",
                "phone", "0909999999"
        ));
        String dSysId = Base64.getUrlEncoder().withoutPadding().encodeToString(sysIdJson.getBytes(StandardCharsets.UTF_8));
        String qrSysId = "vwallet://transfer?d=" + dSysId + "&sig=" + sign(dSysId);

        assertThatThrownBy(() -> qrCodeService.parseQr(3L, new QrParseRequest(qrSysId)))
                .isInstanceOf(UserNotFoundException.class);
        verify(walletRepository, never()).findByUserId(1L);
    }

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
