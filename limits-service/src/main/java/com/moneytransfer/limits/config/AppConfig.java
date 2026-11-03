package com.moneytransfer.limits.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Limits Service API",
        version = "v1",
        description = "Decides whether an account may send an amount (daily limit, per-transfer cap, velocity "
                + "fraud rule) and reserves that usage atomically. Spec at /v3/api-docs(.yaml); UI at /swagger-ui.html."))
public class AppConfig {

    /** One clock for "today" and the velocity window, so tests can control time. Always UTC. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
