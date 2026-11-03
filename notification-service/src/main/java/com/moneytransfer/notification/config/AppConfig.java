package com.moneytransfer.notification.config;

import com.moneytransfer.notification.provider.SimulatedProvider;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Notification Service API (mock)",
        version = "v1",
        description = "Tells the customer a transfer has completed, through a simulated SMS/email provider that can be "
                + "made slow or unreliable. At most one delivery per transfer. Spec at /v3/api-docs(.yaml); "
                + "UI at /swagger-ui.html."))
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    SimulatedProvider simulatedProvider(NotificationProperties properties) {
        NotificationProperties.Provider p = properties.provider();
        SimulatedProvider.Settings settings = new SimulatedProvider.Settings(p.delayMs(), p.failRate(), p.failNext());
        if (p.delayMs() > 0 || p.failRate() > 0 || p.failNext() > 0) {
            log.warn("Simulated provider starts slow or failing: {}", settings);
        }
        // java.util.Random, not RandomGenerator.getDefault(): the default algorithm (L32X64MixRandom) lives in
        // the jdk.random module, which the slim JRE in our Docker image does not include. It worked in
        // IntelliJ (full JDK) and crashed in the container: our Assignment 2 troubleshooting case.
        return new SimulatedProvider(settings, new java.util.Random(), Thread::sleep);
    }
}
