package com.moneytransfer.limits.web.dto;

import com.moneytransfer.limits.application.ReservationService;
import com.moneytransfer.limits.domain.LimitPolicy;
import com.moneytransfer.limits.domain.Reservation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class LimitsDtos {

    private LimitsDtos() { }

    public record ReserveRequest(
            @Schema(example = "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "must be 1-64 letters, digits, '-' or '_'")
            String transferId,

            @Schema(example = "ACC-1001")
            @NotBlank @Size(max = 64)
            String accountId,

            @Schema(example = "1500.00")
            @NotNull
            BigDecimal amount,

            @Schema(example = "INR")
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency code")
            String currency) { }

    public record ReservationResponse(
            String transferId,
            String accountId,
            BigDecimal amount,
            String currency,
            String status,
            LocalDate day,
            BigDecimal usedToday,
            BigDecimal dailyLimit,
            BigDecimal remaining,
            Instant createdAt,
            Instant releasedAt) {

        public static ReservationResponse from(Reservation r, LimitPolicy policy, BigDecimal used) {
            return new ReservationResponse(r.getTransferId(), r.getAccountId(), r.getAmount(), r.getCurrency(),
                    r.getStatus().name(), r.getUsageDay(), used, policy.dailyLimit(),
                    policy.dailyLimit().subtract(used).max(BigDecimal.ZERO), r.getCreatedAt(), r.getReleasedAt());
        }

        public static ReservationResponse from(ReservationService.Result result) {
            return from(result.reservation(), result.policy(), result.usedThatDay());
        }
    }

    public record SetLimitsRequest(
            @Schema(example = "50000.00") @NotNull BigDecimal dailyLimit,
            @Schema(example = "25000.00") @NotNull BigDecimal perTransferLimit) { }

    public record LimitsResponse(
            String accountId,
            LocalDate day,
            BigDecimal dailyLimit,
            BigDecimal perTransferLimit,
            @Schema(description = "true when the account has its own limits instead of the defaults") boolean personal,
            BigDecimal usedToday,
            BigDecimal remaining,
            @Schema(description = "Transfers started within the velocity window") long recentTransfers,
            @Schema(description = "F1: most transfers allowed within the window; 0 = rule off") int velocityMaxTransfers,
            long velocityWindowSeconds) {

        public static LimitsResponse from(ReservationService.Status s) {
            return new LimitsResponse(s.accountId(), s.day(), s.policy().dailyLimit(), s.policy().perTransferLimit(),
                    s.policy().personal(), s.usedToday(), s.policy().dailyLimit().subtract(s.usedToday()).max(BigDecimal.ZERO),
                    s.recentTransfers(), s.velocityMax(), s.velocityWindowSeconds());
        }
    }
}
