package com.moneytransfer.ui.config;

import com.moneytransfer.ui.proxy.ProxyForwarder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    ProxyForwarder proxyForwarder(UiProperties properties) {
        return new ProxyForwarder(properties.services(), properties.connectTimeout(), properties.requestTimeout());
    }
}
