package com.moneytransfer.notification.web;

import com.moneytransfer.notification.application.NotificationService;
import com.moneytransfer.notification.domain.Notification;
import com.moneytransfer.notification.web.dto.NotificationDtos.NotificationDetail;
import com.moneytransfer.notification.web.dto.NotificationDtos.NotificationResponse;
import com.moneytransfer.notification.web.dto.NotificationDtos.NotifyRequest;
import com.moneytransfer.notification.web.error.ApiError;
import com.moneytransfer.notification.web.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Called by the Transfer service after a transfer completes")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Tell the customer a transfer completed",
            description = "202 once delivered, also for a repeat (nothing is sent twice). 503 when the simulated "
                    + "provider fails; retrying the same request may then succeed.")
    @ApiResponse(responseCode = "202", content = @Content(schema = @Schema(implementation = NotificationResponse.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST (unknown channel)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "NOTIFICATION_MISMATCH, INVALID_TEMPLATE, INVALID_MESSAGE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "PROVIDER_DOWN (simulated provider failure)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<NotificationResponse> notify(@Valid @RequestBody NotifyRequest request) {
        Notification n = service.notify(request.transferId(), request.accountId(), request.channel(),
                request.template(), request.message());
        return ResponseEntity.accepted().body(NotificationResponse.from(n));
    }

    @GetMapping
    @Operation(summary = "Notifications for a transfer (or, newest first, for an account), with every provider attempt")
    @ApiResponse(responseCode = "400", description = "MISSING_PARAMETER: give transferId or accountId",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<NotificationDetail> find(@RequestParam(required = false) String transferId,
                                         @RequestParam(required = false) String accountId,
                                         @RequestParam(defaultValue = "50") int limit) {
        if (transferId != null && !transferId.isBlank()) {
            return service.forTransfer(transferId).stream().map(NotificationDetail::from).toList();
        }
        if (accountId != null && !accountId.isBlank()) {
            return service.forAccount(accountId, limit).stream().map(NotificationDetail::from).toList();
        }
        throw new ApiException(400, "MISSING_PARAMETER", "give a transferId or an accountId query parameter");
    }
}
