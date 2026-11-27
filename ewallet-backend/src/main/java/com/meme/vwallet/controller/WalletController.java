package com.meme.vwallet.controller;

import com.meme.vwallet.dto.response.WalletResponse;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final PaymentService paymentService;

    @GetMapping("/me")
    public ResponseEntity<WalletResponse> getMyWallet(@CurrentUser Long userId) {
        WalletResponse response = paymentService.getWalletBalance(userId);
        return ResponseEntity.ok(response);
    }
}
