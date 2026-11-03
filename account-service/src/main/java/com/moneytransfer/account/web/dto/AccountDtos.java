package com.moneytransfer.account.web.dto;

import com.moneytransfer.account.domain.Account;
import com.moneytransfer.account.domain.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() { }

    public record OpenAccountRequest(
            @Schema(example = "ACC-3003")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,64}", message = "must be 3-64 letters, digits, '-' or '_'")
            String accountId,

            @Schema(example = "Priya Nair")
            @NotBlank @Size(max = 100)
            String holderName,

            @Schema(example = "INR")
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency code")
            String currency,

            @Schema(example = "2500.00")
            @NotNull @DecimalMin(value = "0.00", message = "must not be negative")
            @Digits(integer = 17, fraction = 2, message = "may have at most 2 decimal places")
            BigDecimal initialBalance) { }

    public record ChangeStatusRequest(
            @Schema(example = "FROZEN")
            @NotNull
            AccountStatus status) { }

    public record AccountResponse(
            String accountId,
            String holderName,
            String currency,
            BigDecimal balance,
            BigDecimal available,
            BigDecimal onHold,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        public static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getHolderName(), a.getCurrency(), a.getBalance(),
                    a.getAvailable(), a.onHold(), a.getStatus().name(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }
}
