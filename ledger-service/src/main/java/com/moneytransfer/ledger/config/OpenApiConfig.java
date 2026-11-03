package com.moneytransfer.ledger.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Ledger Service API",
        version = "v1",
        description = "Append-only double-entry record of every completed transfer. One balanced journal per "
                + "transfer; mistakes are cancelled by a reversal, never edited. Spec at /v3/api-docs(.yaml); "
                + "UI at /swagger-ui.html."))
public class OpenApiConfig {
}
