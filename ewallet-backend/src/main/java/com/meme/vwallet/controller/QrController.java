package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.QrGenerateRequest;
import com.meme.vwallet.dto.request.QrParseRequest;
import com.meme.vwallet.dto.response.QrGenerateResponse;
import com.meme.vwallet.dto.response.QrParseResponse;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.QrCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for generating and parsing signed transfer QR codes.
 */
@RestController
@RequestMapping("/api/v1/qr")
@RequiredArgsConstructor
public class QrController {

    private final QrCodeService qrCodeService;

    @PostMapping("/generate")
    public ResponseEntity<QrGenerateResponse> generateQr(
            @CurrentUser Long userId,
            @Valid @RequestBody QrGenerateRequest request
    ) {
        return ResponseEntity.ok(qrCodeService.generateQr(userId, request));
    }

    @PostMapping("/parse")
    public ResponseEntity<QrParseResponse> parseQr(
            @CurrentUser Long userId,
            @Valid @RequestBody QrParseRequest request
    ) {
        return ResponseEntity.ok(qrCodeService.parseQr(userId, request));
    }
}
