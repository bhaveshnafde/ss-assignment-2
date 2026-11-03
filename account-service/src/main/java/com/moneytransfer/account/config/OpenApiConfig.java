package com.moneytransfer.account.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Account Service API",
        version = "v1",
        description = "Owns account balances. Holds, captures and releases money for the Transfer service "
                + "without ever overspending. Spec at /v3/api-docs(.yaml); UI at /swagger-ui.html."))
public class OpenApiConfig {
}
