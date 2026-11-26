package com.meme.vwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for recipient phone lookup.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLookupResponse {

    private String phoneNumber;
    private String fullName;

    @JsonProperty("isSelf")
    private boolean isSelf;

    private boolean canReceive;
}
