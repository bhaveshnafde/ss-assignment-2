package com.moneytransfer.ledger.web;

import com.moneytransfer.ledger.application.FaultInjection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo only: switch the Ledger to failing or slow posts without restarting it.
 * Disabled when ADMIN_ENDPOINTS_ENABLED=false.
 */
@RestController
@RequestMapping("/api/v1/admin/faults")
@ConditionalOnProperty(prefix = "ledger", name = "admin-enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Admin (demo)", description = "Fault injection for the Challenge 3C and retry demos")
public class AdminController {

    private final FaultInjection faults;

    public AdminController(FaultInjection faults) {
        this.faults = faults;
    }

    @GetMapping
    @Operation(summary = "Current fault settings")
    public FaultInjection.Settings get() {
        return faults.current();
    }

    @PutMapping
    @Operation(summary = "Change fault settings",
            description = "{\"failPosts\": true} makes POST /journals answer 503; {\"postDelayMs\": 5000} makes it slow.")
    public FaultInjection.Settings put(@RequestBody FaultInjection.Settings settings) {
        return faults.update(settings);
    }
}
