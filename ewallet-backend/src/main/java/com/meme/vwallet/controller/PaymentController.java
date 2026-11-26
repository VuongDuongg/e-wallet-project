package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.response.PageResponse;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.TransactionHistoryItemResponse;
import com.meme.vwallet.dto.response.TransactionResponse;
import com.meme.vwallet.dto.response.VnpayIpnResponse;
import com.meme.vwallet.entity.enums.TransactionStatus;
import com.meme.vwallet.entity.enums.TransactionType;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.PaymentService;
import com.meme.vwallet.service.TransactionQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final TransactionQueryService transactionQueryService;

    @PostMapping("/payments/topup")
    public ResponseEntity<TopupResponse> initiateTopup(
            @CurrentUser Long userId,
            @Valid @RequestBody TopupRequest request,
            HttpServletRequest servletRequest
    ) {
        String clientIp = extractClientIp(servletRequest);
        TopupResponse response = paymentService.initiateTopup(userId, request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<VnpayIpnResponse> webhook(
            @RequestParam(required = false) Map<String, String> queryParams,
            @RequestBody(required = false) Map<String, String> bodyParams
    ) {
        Map<String, String> combinedParams = new HashMap<>();
        if (queryParams != null) {
            combinedParams.putAll(queryParams);
        }
        if (bodyParams != null) {
            combinedParams.putAll(bodyParams);
        }
        VnpayIpnResponse response = paymentService.processWebhook(combinedParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/payments/vnpay-return")
    public ResponseEntity<Map<String, Object>> vnpayReturn(@RequestParam Map<String, String> params) {
        Map<String, Object> result = paymentService.processReturn(params);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/transactions")
    public ResponseEntity<PageResponse<TransactionHistoryItemResponse>> getTransactionHistory(
            @CurrentUser Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "ALL") String direction,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        PageResponse<TransactionHistoryItemResponse> response = transactionQueryService.getHistory(
                userId, page, size, type, status, direction, from, to
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping({"/transactions/{id}", "/payments/transactions/{id}"})
    public ResponseEntity<TransactionResponse> getTransactionStatus(
            @CurrentUser Long userId,
            @PathVariable("id") Long id
    ) {
        TransactionResponse response = paymentService.getTransactionStatus(userId, id);
        return ResponseEntity.ok(response);
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "127.0.0.1";
    }
}
