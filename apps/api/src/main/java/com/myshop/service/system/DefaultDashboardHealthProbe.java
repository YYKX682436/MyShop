package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse.ServiceHealth;
import com.myshop.model.response.SystemStatusResponse.ServiceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

/**
 * Probes only fixed internal service addresses. It never uses Docker's socket
 * and never exposes a health route of its own.
 */
@Component
public class DefaultDashboardHealthProbe implements DashboardHealthProbe {

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final URI webUri;
    private final String caddyHost;
    private final int caddyPort;
    private final int timeoutMillis;

    public DefaultDashboardHealthProbe(
            JdbcTemplate jdbcTemplate,
            @Value("${dashboard.system.web-url:http://web:3000/}") String webUrl,
            @Value("${dashboard.system.caddy-host:caddy}") String caddyHost,
            @Value("${dashboard.system.caddy-port:80}") int caddyPort,
            @Value("${dashboard.system.timeout-ms:1500}") long timeoutMillis) {
        this.jdbcTemplate = jdbcTemplate;
        this.webUri = safeUri(webUrl, "http://web:3000/");
        this.caddyHost = caddyHost == null || caddyHost.isBlank() ? "caddy" : caddyHost;
        this.caddyPort = caddyPort;
        this.timeoutMillis = Math.max(250, Math.min((int) timeoutMillis, 5000));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ServiceHealth probeDatabase() {
        try {
            Integer result = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout((int) Math.max(1, (timeoutMillis + 999) / 1000));
                    try (ResultSet rows = statement.executeQuery("SELECT 1")) {
                        return rows.next() ? rows.getInt(1) : null;
                    }
                }
            });
            return result != null && result == 1 ? up() : down();
        } catch (Exception e) {
            return down();
        }
    }

    @Override
    public ServiceHealth probeWeb() {
        try {
            // A HEAD probe checks that the Web server can accept the request without
            // downloading the complete server-rendered homepage on every dashboard
            // refresh. The Docker healthcheck uses the same lightweight semantics.
            HttpRequest request = HttpRequest.newBuilder(webUri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 400 ? up() : down();
        } catch (IOException e) {
            return down();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down();
        } catch (RuntimeException e) {
            return down();
        }
    }

    @Override
    public ServiceHealth probeCaddy() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(caddyHost, caddyPort), timeoutMillis);
            return up();
        } catch (IOException | RuntimeException e) {
            return down();
        }
    }

    private static URI safeUri(String configured, String fallback) {
        try {
            URI uri = URI.create(configured == null ? "" : configured.trim());
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                return uri;
            }
        } catch (RuntimeException ignored) {
            // Use the fixed internal default below.
        }
        return URI.create(fallback);
    }

    private static ServiceHealth up() {
        return new ServiceHealth(ServiceStatus.UP);
    }

    private static ServiceHealth down() {
        return new ServiceHealth(ServiceStatus.DOWN);
    }
}
