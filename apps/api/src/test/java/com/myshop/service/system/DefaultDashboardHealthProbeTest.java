package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse.ServiceStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DefaultDashboardHealthProbeTest {

    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probeWebUsesLightweightGetAndAcceptsSuccessfulResponse() {
        ServiceStatus status = probeWeb().probeWeb().status();

        assertEquals(ServiceStatus.UP, status);
        assertEquals("GET", requestMethod.get());
        assertEquals("/robots.txt", requestPath.get());
    }

    @Test
    void probeWebTreatsHttpErrorAsDown() {
        responseStatus.set(503);

        ServiceStatus status = probeWeb().probeWeb().status();

        assertEquals(ServiceStatus.DOWN, status);
        assertEquals("GET", requestMethod.get());
        assertEquals("/robots.txt", requestPath.get());
    }

    @Test
    void probeWebTreatsConnectionFailureAsDown() throws IOException {
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        DefaultDashboardHealthProbe probe = new DefaultDashboardHealthProbe(
                mock(JdbcTemplate.class),
                "http://127.0.0.1:%d/".formatted(unusedPort),
                "127.0.0.1",
                1,
                250);

        assertEquals(ServiceStatus.DOWN, probe.probeWeb().status());
    }

    private DefaultDashboardHealthProbe probeWeb() {
        return new DefaultDashboardHealthProbe(
                mock(JdbcTemplate.class),
                "http://127.0.0.1:%d/robots.txt".formatted(server.getAddress().getPort()),
                "127.0.0.1",
                1,
                1000);
    }
}
