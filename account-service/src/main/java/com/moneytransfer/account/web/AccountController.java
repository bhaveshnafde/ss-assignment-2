package com.moneytransfer.account.web;

import com.moneytransfer.account.application.AccountService;
import com.moneytransfer.account.persistence.HoldJpaRepository;
import com.moneytransfer.account.web.dto.AccountDtos.AccountResponse;
import com.moneytransfer.account.web.dto.AccountDtos.ChangeStatusRequest;
import com.moneytransfer.account.web.dto.AccountDtos.OpenAccountRequest;
import com.moneytransfer.account.web.dto.HoldDtos.HoldResponse;
import com.moneytransfer.account.web.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Open accounts, read balances, freeze or close. Not called by the Transfer service.")
public class AccountController {

    private final AccountService accounts;
    private final HoldJpaRepository holds;

    public AccountController(AccountService accounts, HoldJpaRepository holds) {
        this.accounts = accounts;
        this.holds = holds;
    }

    @PostMapping
    @Operation(summary = "Open an account with an opening balance")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ALREADY_EXISTS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        AccountResponse body = AccountResponse.from(accounts.open(request.accountId(), request.holderName(),
                request.currency(), request.initialBalance()));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.accountId())).body(body);
    }

    @GetMapping
    @Operation(summary = "List all accounts with balance, available and on-hold amounts")
    public List<AccountResponse> list() {
        return accounts.list().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get one account")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AccountResponse get(@PathVariable String accountId) {
        return AccountResponse.from(accounts.get(accountId));
    }

    @GetMapping("/{accountId}/holds")
    @Operation(summary = "Holds placed on this account as the source, newest first")
    public List<HoldResponse> holdsOf(@PathVariable String accountId) {
        accounts.get(accountId);
        return holds.findBySourceAccountIdOrderByCreatedAtDesc(accountId).stream().map(HoldResponse::from).toList();
    }

    @PatchMapping("/{accountId}/status")
    @Operation(summary = "Freeze, unfreeze or close an account",
            description = "ACTIVE <-> FROZEN; ACTIVE or FROZEN -> CLOSED only with zero balance and nothing on hold.")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_CHANGE, ACCOUNT_NOT_EMPTY",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AccountResponse changeStatus(@PathVariable String accountId, @Valid @RequestBody ChangeStatusRequest request) {
        return AccountResponse.from(accounts.changeStatus(accountId, request.status()));
    }
}
