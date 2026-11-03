package com.moneytransfer.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TransferServiceApplication {

    public static void main(String[] args) {
        // Run in UTC whatever the machine's timezone. Timestamps are then stored consistently, and
        // the Postgres driver no longer sends legacy zone names (e.g. "Asia/Calcutta" from Windows)
        // that the server rejects with: invalid value for parameter "TimeZone".
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
