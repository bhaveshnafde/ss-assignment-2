package com.moneytransfer.ledger.web.dto;

import com.moneytransfer.ledger.domain.Direction;
import com.moneytransfer.ledger.domain.EntryLine;
import com.moneytransfer.ledger.domain.Journal;
import com.moneytransfer.ledger.domain.JournalEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JournalDtos {

    private JournalDtos() { }

    public record PostJournalRequest(
            @Schema(example = "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "must be 1-64 letters, digits, '-' or '_'")
            String transferId,

            @Schema(example = "INR")
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency code")
            String currency,

            @NotEmpty @Size(max = 50)
            List<@Valid EntryRequest> entries) {

        public List<EntryLine> lines() {
            return entries.stream().map(e -> new EntryLine(e.accountId(), e.direction(), e.amount())).toList();
        }
    }

    public record EntryRequest(
            @Schema(example = "ACC-1001")
            @NotBlank @Size(max = 64)
            String accountId,

            @Schema(example = "DEBIT")
            @NotNull
            Direction direction,

            @Schema(example = "1500.00")
            @NotNull
            BigDecimal amount) { }

    public record EntryResponse(String accountId, String direction, BigDecimal amount) { }

    public record JournalResponse(
            String journalId,
            String transferId,
            String type,
            String currency,
            @Schema(description = "On a REVERSAL: the journal it cancels") String reverses,
            @Schema(description = "On a TRANSFER journal: the reversal that cancelled it, if any") String reversedBy,
            Instant postedAt,
            List<EntryResponse> entries) {

        public static JournalResponse from(Journal j, Optional<Journal> reversal) {
            return new JournalResponse(
                    j.journalId(),
                    j.getTransferId(),
                    j.getType().name(),
                    j.getCurrency(),
                    Journal.publicId(j.getReversesJournalId()),
                    reversal.map(Journal::journalId).orElse(null),
                    j.getPostedAt(),
                    j.getEntries().stream()
                            .map(e -> new EntryResponse(e.getAccountId(), e.getDirection().name(), e.getAmount()))
                            .toList());
        }
    }

    /** One line of an account's history, with the journal it belongs to. */
    public record AccountEntryResponse(
            String journalId,
            String transferId,
            String type,
            String direction,
            BigDecimal amount,
            String currency,
            Instant postedAt) {

        public static AccountEntryResponse from(JournalEntry e) {
            Journal j = e.getJournal();
            return new AccountEntryResponse(j.journalId(), j.getTransferId(), j.getType().name(),
                    e.getDirection().name(), e.getAmount(), j.getCurrency(), j.getPostedAt());
        }
    }
}
