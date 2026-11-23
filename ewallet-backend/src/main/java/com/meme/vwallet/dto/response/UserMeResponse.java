package com.meme.vwallet.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMeResponse {

    private Long userId;
    private String phoneNumber;
    private String fullName;
    private Boolean hasPin;
    private String status;
    private Instant createdAt;
}
