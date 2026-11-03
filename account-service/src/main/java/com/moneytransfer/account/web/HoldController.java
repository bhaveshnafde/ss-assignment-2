package com.moneytransfer.account.web;

import com.moneytransfer.account.application.HoldService;
import com.moneytransfer.account.application.HoldTransactions;
import com.moneytransfer.account.domain.RuleViolationException;
import com.moneytransfer.account.persistence.HoldJpaRepository;
import com.moneytransfer.account.web.dto.HoldDtos.HoldResponse;
import com.moneytransfer.account.web.dto.HoldDtos.PlaceHoldRequest;
import com.moneytransfer.account.web.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** The three calls the Transfer service makes. Each is safe to repeat for the same transferId. */
@RestController
@RequestMapping("/api/v1/holds")
@Tag(name = "Holds", description = "Called by the Transfer service: hold (step 2), capture (step 4), release (undo)")
public class HoldController {

    private final HoldService holdService;
    private final HoldTransactions tx;
    private final HoldJpaRepository holds;

    public HoldController(HoldService holdService, HoldTransactions tx, HoldJpaRepository holds) {
        this.holdService = holdService;
        this.tx = tx;
        this.holds = holds;
    }

    @PostMapping
    @Operation(summary = "Set money aside on the source account for a transfer",
            description = "201 when the hold is new, 200 when this transferId already has the same hold.")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "200", description = "Already held; nothing changed",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_FUNDS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "ACCOUNT_NOT_ACTIVE, CURRENCY_MISMATCH, SAME_ACCOUNT, INVALID_AMOUNT, HOLD_MISMATCH",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<HoldResponse> place(@Valid @RequestBody PlaceHoldRequest request) {
        HoldService.PlaceResult result = holdService.place(request.transferId(), request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), request.currency());
        HoldResponse body = HoldResponse.from(result.hold());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/holds/" + body.transferId())).body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get the hold for a transfer")
    @ApiResponse(responseCode = "404", description = "HOLD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HoldResponse get(@PathVariable String transferId) {
        return holds.findById(transferId).map(HoldResponse::from)
                .orElseThrow(() -> new RuleViolationException(404, "HOLD_NOT_FOUND", "no hold for transfer " + transferId));
    }

    @PostMapping("/{transferId}/capture")
    @Operation(summary = "Move the held money: debit source, credit destination, in one transaction")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "404", description = "HOLD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "HOLD_NOT_ACTIVE (already released)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HoldResponse capture(@PathVariable String transferId) {
        return HoldResponse.from(tx.capture(transferId).hold());
    }

    @PostMapping("/{transferId}/release")
    @Operation(summary = "Give the held money back to the source (saga undo)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "404", description = "HOLD_NOT_FOUND (Transfer treats this as already undone)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "HOLD_ALREADY_CAPTURED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HoldResponse release(@PathVariable String transferId) {
        return HoldResponse.from(tx.release(transferId).hold());
    }
}
