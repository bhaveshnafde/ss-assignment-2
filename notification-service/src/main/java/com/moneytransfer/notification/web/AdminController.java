package com.moneytransfer.notification.web;

import com.moneytransfer.notification.provider.SimulatedProvider;
import com.moneytransfer.notification.web.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo only: make the simulated provider slow or failing without restarting.
 * Disabled when ADMIN_ENDPOINTS_ENABLED=false.
 */
@RestController
@RequestMapping("/api/v1/admin/provider")
@ConditionalOnProperty(prefix = "notification", name = "admin-enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Admin (demo)", description = "Switches for the simulated provider")
public class AdminController {

    /** All three fields; an omitted number counts as 0. */
    public record SettingsRequest(long delayMs, double failRate, int failNext) { }

    private final SimulatedProvider provider;

    public AdminController(SimulatedProvider provider) {
        this.provider = provider;
    }

    @GetMapping
    @Operation(summary = "Current provider settings")
    public SimulatedProvider.Settings get() {
        return provider.settings();
    }

    @PutMapping
    @Operation(summary = "Change provider settings",
            description = "{\"delayMs\": 5000} slow; {\"failRate\": 0.5} half fail; {\"failNext\": 2} next two fail; all 0 = normal.")
    public SimulatedProvider.Settings put(@RequestBody SettingsRequest request) {
        try {
            return provider.update(new SimulatedProvider.Settings(request.delayMs(), request.failRate(), request.failNext()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, "INVALID_SETTING", e.getMessage());
        }
    }
}
