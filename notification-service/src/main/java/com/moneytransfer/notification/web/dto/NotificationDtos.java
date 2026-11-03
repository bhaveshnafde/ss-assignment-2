package com.moneytransfer.notification.web.dto;

import com.moneytransfer.notification.application.NotificationService;
import com.moneytransfer.notification.domain.Channel;
import com.moneytransfer.notification.domain.Notification;
import com.moneytransfer.notification.domain.NotificationAttempt;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class NotificationDtos {

    private NotificationDtos() { }

    public record NotifyRequest(
            @Schema(example = "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "must be 1-64 letters, digits, '-' or '_'")
            String transferId,

            @Schema(example = "ACC-1001")
            @NotBlank @Size(max = 64)
            String accountId,

            @Schema(example = "SMS")
            @NotNull
            Channel channel,

            @Schema(example = "TRANSFER_COMPLETED")
            @NotBlank @Size(max = 40)
            String template,

            @Schema(example = "You sent 1500.00 INR to ACC-2002. Ref 3f6c2a1e-...")
            @NotBlank @Size(max = 500)
            String message) { }

    public record NotificationResponse(
            String notificationId,
            String transferId,
            String accountId,
            String channel,
            String template,
            String status,
            int attempts,
            String providerMessageId,
            Instant sentAt) {

        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.notificationId(), n.getTransferId(), n.getAccountId(), n.getChannel().name(),
                    n.getTemplate(), n.getStatus().name(), n.getAttempts(), n.getProviderMessageId(), n.getSentAt());
        }
    }

    public record AttemptResponse(int attemptNo, String outcome, String error, long durationMs, Instant at) {

        public static AttemptResponse from(NotificationAttempt a) {
            return new AttemptResponse(a.getAttemptNo(), a.getOutcome().name(), a.getError(), a.getDurationMs(), a.getAttemptedAt());
        }
    }

    /** A notification with its message and every provider attempt, for the read endpoints. */
    public record NotificationDetail(
            String notificationId,
            String transferId,
            String accountId,
            String channel,
            String template,
            String message,
            String status,
            int attempts,
            String lastError,
            String providerMessageId,
            Instant createdAt,
            Instant sentAt,
            List<AttemptResponse> history) {

        public static NotificationDetail from(NotificationService.View v) {
            Notification n = v.notification();
            return new NotificationDetail(n.notificationId(), n.getTransferId(), n.getAccountId(), n.getChannel().name(),
                    n.getTemplate(), n.getMessage(), n.getStatus().name(), n.getAttempts(), n.getLastError(),
                    n.getProviderMessageId(), n.getCreatedAt(), n.getSentAt(),
                    v.history().stream().map(AttemptResponse::from).toList());
        }
    }
}
