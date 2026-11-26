package com.meme.vwallet.controller;

import com.meme.vwallet.dto.request.PinSetupRequest;
import com.meme.vwallet.dto.response.UserLookupResponse;
import com.meme.vwallet.dto.response.UserMeResponse;
import com.meme.vwallet.entity.User;
import com.meme.vwallet.security.CurrentUser;
import com.meme.vwallet.service.UserLookupService;
import com.meme.vwallet.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserLookupService userLookupService;

    @PostMapping("/pin")
    public ResponseEntity<Map<String, Object>> setupPin(
            @CurrentUser Long userId,
            @Valid @RequestBody PinSetupRequest request
    ) {
        if (!request.getPin().equals(request.getConfirmPin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã PIN xác nhận không khớp");
        }

        userService.setInitialPin(userId, request.getPin());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Cài đặt mã PIN giao dịch thành công"
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getCurrentUser(@CurrentUser Long userId) {
        User user = userService.findById(userId);

        UserMeResponse response = UserMeResponse.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .fullName(user.getFullName())
                .hasPin(user.hasPin())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/lookup")
    public ResponseEntity<UserLookupResponse> lookupUser(
            @CurrentUser Long userId,
            @RequestParam("phone") String phone
    ) {
        return ResponseEntity.ok(userLookupService.lookupByPhone(userId, phone));
    }
}
