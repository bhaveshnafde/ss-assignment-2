package com.moneytransfer.ledger.web;

import com.moneytransfer.ledger.application.JournalService;
import com.moneytransfer.ledger.domain.Journal;
import com.moneytransfer.ledger.domain.JournalType;
import com.moneytransfer.ledger.web.dto.JournalDtos.AccountEntryResponse;
import com.moneytransfer.ledger.web.dto.JournalDtos.JournalResponse;
import com.moneytransfer.ledger.web.dto.JournalDtos.PostJournalRequest;
import com.moneytransfer.ledger.web.error.ApiError;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/journals")
@Tag(name = "Journals", description = "Called by the Transfer service: post (step 3) and reversal (undo). Append-only.")
public class JournalController {

    private final JournalService service;

    public JournalController(JournalService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Record the balanced journal for a transfer",
            description = "201 when new, 200 when this transferId already has the same journal.")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = JournalResponse.class)))
    @ApiResponse(responseCode = "200", description = "Already posted; nothing changed",
            content = @Content(schema = @Schema(implementation = JournalResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "UNBALANCED_JOURNAL, INVALID_ENTRY, JOURNAL_MISMATCH",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "LEDGER_FORCED_FAILURE (demo fault switch)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JournalResponse> post(@Valid @RequestBody PostJournalRequest request) {
        JournalService.Result result = service.post(request.transferId(), request.currency(), request.lines());
        JournalResponse body = JournalResponse.from(result.journal(), result.reversal());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/journals/" + body.transferId())).body(body)
                : ResponseEntity.ok(body);
    }

    @PostMapping("/{transferId}/reversal")
    @Operation(summary = "Cancel a transfer's journal by posting its mirror image (saga undo)",
            description = "201 when new, 200 when already reversed.")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = JournalResponse.class)))
    @ApiResponse(responseCode = "404", description = "JOURNAL_NOT_FOUND (Transfer treats this as already undone)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JournalResponse> reverse(@PathVariable String transferId) {
        JournalService.Result result = service.reverse(transferId);
        JournalResponse body = JournalResponse.from(result.journal(), Optional.empty());
        return result.created() ? ResponseEntity.status(201).body(body) : ResponseEntity.ok(body);
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "A transfer's journal, then its reversal if any")
    @ApiResponse(responseCode = "404", description = "JOURNAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<JournalResponse> forTransfer(@PathVariable String transferId) {
        List<Journal> found = service.forTransfer(transferId);
        Optional<Journal> reversal = found.stream().filter(j -> j.getType() == JournalType.REVERSAL).findFirst();
        return found.stream()
                .map(j -> JournalResponse.from(j, j.getType() == JournalType.TRANSFER ? reversal : Optional.empty()))
                .toList();
    }

    @GetMapping
    @Operation(summary = "An account's journal lines, newest first")
    @ApiResponse(responseCode = "400", description = "MISSING_PARAMETER (accountId)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<AccountEntryResponse> forAccount(@RequestParam String accountId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return service.entriesFor(accountId, limit).stream().map(AccountEntryResponse::from).toList();
    }
}
