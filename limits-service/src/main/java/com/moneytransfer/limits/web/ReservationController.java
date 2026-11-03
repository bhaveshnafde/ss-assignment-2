package com.moneytransfer.limits.web;

import com.moneytransfer.limits.application.LimitPolicyService;
import com.moneytransfer.limits.application.ReservationService;
import com.moneytransfer.limits.domain.Reservation;
import com.moneytransfer.limits.persistence.UsageRepository;
import com.moneytransfer.limits.web.dto.LimitsDtos.ReservationResponse;
import com.moneytransfer.limits.web.dto.LimitsDtos.ReserveRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** The two calls the Transfer service makes. Both are safe to repeat for the same transferId. */
@RestController
@RequestMapping("/api/v1/limit-reservations")
@Tag(name = "Limit reservations", description = "Called by the Transfer service: reserve (step 1) and release (undo)")
public class ReservationController {

    private final ReservationService service;
    private final LimitPolicyService policies;
    private final UsageRepository usage;

    public ReservationController(ReservationService service, LimitPolicyService policies, UsageRepository usage) {
        this.service = service;
        this.policies = policies;
        this.usage = usage;
    }

    @PostMapping
    @Operation(summary = "Reserve part of the account's daily limit for a transfer",
            description = "201 when new, 200 when this transferId already holds the same reservation.")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "200", description = "Already reserved; nothing changed",
            content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "409", description = "RESERVATION_RELEASED (this transfer was already undone)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "LIMIT_EXCEEDED, VELOCITY_LIMIT, INVALID_AMOUNT, RESERVATION_MISMATCH",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        ReservationService.Result result = service.reserve(request.transferId(), request.accountId(),
                request.amount(), request.currency());
        ReservationResponse body = ReservationResponse.from(result);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/limit-reservations/" + body.transferId())).body(body)
                : ResponseEntity.ok(body);
    }

    @DeleteMapping("/{transferId}")
    @Operation(summary = "Release a transfer's reservation (saga undo)",
            description = "204 when released, also when it was already released. 404 when there was nothing to release.")
    @ApiResponse(responseCode = "204", description = "Released (or already released)")
    @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND (Transfer treats this as already undone)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> release(@PathVariable String transferId) {
        service.release(transferId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get a transfer's reservation")
    @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ReservationResponse get(@PathVariable String transferId) {
        Reservation r = service.get(transferId);
        return ReservationResponse.from(r, policies.policyFor(r.getAccountId()), usage.usedOn(r.getAccountId(), r.getUsageDay()));
    }
}
