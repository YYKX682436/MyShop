package com.myshop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Provides the single business clock used by dashboard time boundaries. */
@Configuration(proxyBeanMethods = false)
public class DashboardClockConfig {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock dashboardClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
