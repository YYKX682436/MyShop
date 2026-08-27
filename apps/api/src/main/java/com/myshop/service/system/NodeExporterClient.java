package com.myshop.service.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Internal-only client; the URL is deployment configuration, never request input. */
@Component
public class NodeExporterClient {

    private final URI metricsUri;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public NodeExporterClient(
            @Value("${dashboard.node-exporter-url:http://node-exporter:9100}") String configuredUrl,
            @Value("${dashboard.system.timeout-ms:1500}") long timeoutMillis) {
        this.metricsUri = metricsUri(configuredUrl);
        long boundedTimeout = Math.max(250, Math.min(timeoutMillis, 5000));
        this.requestTimeout = Duration.ofMillis(boundedTimeout);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Optional<String> scrape() {
        try {
            HttpRequest request = HttpRequest.newBuilder(metricsUri)
                    .timeout(requestTimeout)
                    .header("Accept", "text/plain; version=0.0.4")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body() != null && !response.body().isBlank()) {
                return Optional.of(response.body());
            }
        } catch (IOException e) {
            // System status is deliberately best-effort; callers receive unavailable.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // A malformed deployment URL must not turn the admin endpoint into a 500.
        }
        return Optional.empty();
    }

    private static URI metricsUri(String configuredUrl) {
        try {
            URI uri = URI.create(configuredUrl == null ? "" : configuredUrl.trim());
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                String path = uri.getPath();
                if (path == null || path.isBlank() || "/".equals(path)) {
                    return URI.create(uri.toString().replaceAll("/+$", "") + "/metrics");
                }
                return uri;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the safe internal default.
        }
        return URI.create("http://node-exporter:9100/metrics");
    }
}
