package com.moneytransfer.limits;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LimitsServiceApplication {

    public static void main(String[] args) {
        // Run in UTC whatever the machine's timezone. "Today" for the daily limit is then the same day
        // on every machine, and the Postgres driver no longer sends legacy zone names
        // (e.g. "Asia/Calcutta" from Windows) that the server rejects.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(LimitsServiceApplication.class, args);
    }
}
