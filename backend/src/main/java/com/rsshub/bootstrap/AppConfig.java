package com.rsshub.bootstrap;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConfigurationPropertiesScan("com.rsshub")
@EnableScheduling
public class AppConfig {
}
