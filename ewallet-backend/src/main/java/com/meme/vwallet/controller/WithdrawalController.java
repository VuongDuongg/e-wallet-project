package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.WithdrawalRequest;
import com.meme.vwallet.dto.response.WithdrawalResponse;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.withdrawal.WithdrawalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @PostMapping
    public ResponseEntity<WithdrawalResponse> withdraw(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CurrentUser Long userId,
            @Valid @RequestBody WithdrawalRequest request
    ) {
        WithdrawalResponse response = withdrawalService.withdraw(userId, idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
