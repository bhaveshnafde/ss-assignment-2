package com.moneytransfer.account.web.dto;

import com.moneytransfer.account.domain.Hold;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public final class HoldDtos {

    private HoldDtos() { }

    public record PlaceHoldRequest(
            @Schema(example = "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "must be 1-64 letters, digits, '-' or '_'")
            String transferId,

            @Schema(example = "ACC-1001")
            @NotBlank
            String sourceAccountId,

            @Schema(example = "ACC-2002")
            @NotBlank
            String destinationAccountId,

            @Schema(example = "1500.00")
            @NotNull
            BigDecimal amount,

            @Schema(example = "INR")
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency code")
            String currency) { }

    public record HoldResponse(
            String transferId,
            String sourceAccountId,
            String destinationAccountId,
            BigDecimal amount,
            String currency,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        public static HoldResponse from(Hold h) {
            return new HoldResponse(h.getTransferId(), h.getSourceAccountId(), h.getDestinationAccountId(),
                    h.getAmount(), h.getCurrency(), h.getStatus().name(), h.getCreatedAt(), h.getUpdatedAt());
        }
    }
}
