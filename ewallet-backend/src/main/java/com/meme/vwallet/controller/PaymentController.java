package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.TopupRequest;
import com.meme.vwallet.dto.response.TopupResponse;
import com.meme.vwallet.dto.response.TransactionResponse;
import com.meme.vwallet.dto.response.VnpayIpnResponse;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.PaymentService;
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

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

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

    @GetMapping({"/transactions/{id}", "/payments/transactions/{id}"})
    public ResponseEntity<TransactionResponse> getTransactionStatus(@PathVariable("id") Long id) {
        TransactionResponse response = paymentService.getTransactionStatus(id);
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
