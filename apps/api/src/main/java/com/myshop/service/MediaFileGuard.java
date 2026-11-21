package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Coordinates filesystem publication/deletion with the database write guards. */
@Slf4j
@Service
public class MediaFileGuard {
    private static final long LOCK_ID = 724385982937L;
    private final DataSource dataSource;

    public MediaFileGuard(DataSource dataSource) { this.dataSource = dataSource; }

    @FunctionalInterface
    public interface Work<T> { T run(Connection connection) throws Exception; }

    public <T> T exclusive(boolean requirePostgres, Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                if (requirePostgres) throw new BusinessException(ErrorCode.SERVER_ERROR, "资源操作需要 PostgreSQL 安全保护");
                return work.run(null); // H2 is used only by isolated application tests.
            }
            connection.setAutoCommit(false);
            boolean locked = false;
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '3s'");
                    statement.execute("SELECT pg_advisory_lock(" + LOCK_ID + ")");
                    locked = true;
                    statement.execute("SET LOCAL statement_timeout = '15s'");
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } finally {
                try { connection.rollback(); } catch (SQLException ignored) { }
                if (locked) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("SELECT pg_advisory_unlock(" + LOCK_ID + ")");
                        connection.commit();
                    } catch (SQLException failure) {
                        // A pooled connection must never be returned with a session lock.
                        try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
                        throw failure;
                    }
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Managed image operation failed: {}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVER_ERROR, "资源操作失败或数据正在变更，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    public static void restorePublishedFile(Connection connection, String filename) throws SQLException {
        if (connection == null) return;
        try (var query = connection.prepareStatement("DELETE FROM media_deleted_files WHERE filename = ?")) {
            query.setString(1, filename.toLowerCase(java.util.Locale.ROOT));
            query.executeUpdate();
        }
    }
}
