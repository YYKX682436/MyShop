package com.myshop.service.impl;

import com.myshop.MyShopApplication;
import com.myshop.service.PaymentService;
import org.springframework.boot.SpringApplication;

import java.math.BigDecimal;
import java.util.UUID;

/** Child JVM used only by the isolated PostgreSQL process-crash test. */
public final class PaymentCrashWorker {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(MyShopApplication.class,
                "--spring.datasource.url=" + args[0],
                "--spring.datasource.username=" + args[1],
                "--spring.datasource.password=" + args[2],
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.minimum-idle=0",
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.flyway.enabled=true",
                "--server.port=0",
                "--upload.path=" + args[5] + "/uploads",
                "--private-upload.path=" + args[5] + "/private-uploads",
                "--jwt.secret=crash-worker-fake-secret-with-at-least-64-characters-for-local-tests-only",
                "--fulfillment.retry-initial-delay-ms=3600000",
                "--supplier.purchase-enabled=false")) {
            context.getBean(PaymentService.class).createPayment(UUID.fromString(args[3]), args[4], new BigDecimal("10.00"), "pc");
        }
    }
}
