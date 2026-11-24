package com.myshop.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** A committed deletion remains private even if filesystem cleanup failed. */
@Service
public class PublicMediaVisibility {
    private final UploadStorage storage;
    private final JdbcTemplate jdbc;

    public PublicMediaVisibility(UploadStorage storage, JdbcTemplate jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public boolean visible(String relativePath) {
        if (!storage.isSafePublicFile(relativePath)) return false;
        try {
            List<Boolean> states = jdbc.query("""
                    SELECT deleted_at IS NULL AND processing_status = 'READY' FROM media_assets
                    WHERE storage_path=? OR display_path=? OR thumbnail_path=? OR clear_path=?
                       OR (deleted_at IS NOT NULL AND position('/' in storage_path)=0
                           AND position('/' in ?)=0 AND lower(storage_path)=lower(?))
                    """, (rs, n) -> rs.getBoolean(1), relativePath, relativePath, relativePath, relativePath, relativePath, relativePath);
            if (!states.isEmpty()) return states.stream().allMatch(Boolean.TRUE::equals);
            // Valid pre-index legacy files remain readable during upgrade. New
            // video/variant directories require an active database asset.
            return !relativePath.contains("/") || relativePath.matches("(?i)avatars/avatar-[0-9a-f-]+\\.(jpg|jpeg|png|webp|gif|avif)");
        } catch (DataAccessException exception) {
            // Fail closed if the deletion state cannot be checked.
            return false;
        }
    }
}
