package com.moneytransfer.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

/**
 * The demo dashboard. Open http://localhost:8080 once the other five services are running.
 * It has no database and no business logic: it serves one page and forwards that page's calls.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UiServiceApplication {

    public static void main(String[] args) {
        // Same as the other services: run in UTC whatever the machine's time zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(UiServiceApplication.class, args);
    }
}
