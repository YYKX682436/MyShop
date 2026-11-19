package com.myshop.service.impl;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Docker in CI, or an explicitly named disposable loopback database for local tests. */
final class IntegrationPostgres implements AutoCloseable {
    private final String externalUrl = System.getenv("MYSHOP_TEST_POSTGRES_URL");
    private PostgreSQLContainer<?> container;

    boolean available() {
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!externalUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/myshop_test_[a-z0-9_]+")) {
                throw new IllegalArgumentException("Integration database must be a disposable loopback myshop_test_* database");
            }
            return true;
        }
        return DockerClientFactory.instance().isDockerAvailable();
    }

    private synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
        return container;
    }

    String getJdbcUrl() {
        return externalUrl != null && !externalUrl.isBlank() ? externalUrl : container().getJdbcUrl();
    }

    String getUsername() {
        return externalUrl != null && !externalUrl.isBlank()
                ? System.getenv().getOrDefault("MYSHOP_TEST_POSTGRES_USER", "myshop_test") : container().getUsername();
    }

    String getPassword() {
        return externalUrl != null && !externalUrl.isBlank()
                ? System.getenv().getOrDefault("MYSHOP_TEST_POSTGRES_PASSWORD", "") : container().getPassword();
    }

    @Override
    public void close() {
        if (container != null) container.stop();
    }
}
