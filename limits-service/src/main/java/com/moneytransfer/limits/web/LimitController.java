package com.moneytransfer.limits.web;

import com.moneytransfer.limits.application.LimitPolicyService;
import com.moneytransfer.limits.application.ReservationService;
import com.moneytransfer.limits.web.dto.LimitsDtos.LimitsResponse;
import com.moneytransfer.limits.web.dto.LimitsDtos.SetLimitsRequest;
import com.moneytransfer.limits.web.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An account's limits and today's usage; setting personal limits. Not called by the Transfer service. */
@RestController
@RequestMapping("/api/v1/limits")
@Tag(name = "Limits", description = "Read an account's limits and usage; set or clear personal limits")
public class LimitController {

    private final ReservationService reservations;
    private final LimitPolicyService policies;

    public LimitController(ReservationService reservations, LimitPolicyService policies) {
        this.reservations = reservations;
        this.policies = policies;
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "The account's limits, today's usage, and recent transfers for the velocity rule",
            description = "An account with no transfers today has usedToday 0; Limits does not need to know which accounts exist.")
    public LimitsResponse get(@PathVariable String accountId) {
        return LimitsResponse.from(reservations.status(accountId));
    }

    @PutMapping("/{accountId}")
    @Operation(summary = "Give the account its own limits instead of the defaults")
    @ApiResponse(responseCode = "422", description = "INVALID_LIMIT (not positive, more than 2 decimals, or per-transfer above daily)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public LimitsResponse set(@PathVariable String accountId, @Valid @RequestBody SetLimitsRequest request) {
        policies.setOverride(accountId, request.dailyLimit(), request.perTransferLimit());
        return LimitsResponse.from(reservations.status(accountId));
    }

    @DeleteMapping("/{accountId}")
    @Operation(summary = "Put the account back on the default limits")
    @ApiResponse(responseCode = "204")
    public ResponseEntity<Void> clear(@PathVariable String accountId) {
        policies.clearOverride(accountId);
        return ResponseEntity.noContent().build();
    }
}
