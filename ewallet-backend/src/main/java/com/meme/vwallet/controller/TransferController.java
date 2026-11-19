package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.TransferRequest;
import com.meme.vwallet.dto.response.TransferResponse;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CurrentUser Long userId,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferResponse response = transferService.transfer(userId, idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
