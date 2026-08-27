package com.myshop.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses the line-oriented import format without ever deleting an internal
 * blank line. The row number is part of the validation contract because
 * silently compacting one column would pair unrelated values.
 */
public final class CardContentParser {

    private CardContentParser() {
    }

    public static ParsedColumns parse(List<String> rawColumns) {
        if (rawColumns == null || rawColumns.isEmpty()) {
            throw new InvalidContentException("至少需要一列卡券内容");
        }

        List<List<String>> columns = new ArrayList<>(rawColumns.size());
        for (int columnIndex = 0; columnIndex < rawColumns.size(); columnIndex++) {
            columns.add(parseColumn(rawColumns.get(columnIndex), columnIndex + 1));
        }

        int expected = columns.get(0).size();
        for (int i = 1; i < columns.size(); i++) {
            if (columns.get(i).size() != expected) {
                throw new InvalidContentException("各卡券内容数量不一致：第1列 " + expected
                        + " 条，第" + (i + 1) + "列 " + columns.get(i).size()
                        + " 条，请补齐后再导入");
            }
        }

        List<List<String>> rows = new ArrayList<>(expected);
        for (int rowIndex = 0; rowIndex < expected; rowIndex++) {
            List<String> row = new ArrayList<>(columns.size());
            for (List<String> column : columns) {
                row.add(column.get(rowIndex));
            }
            rows.add(Collections.unmodifiableList(row));
        }
        return new ParsedColumns(columns, rows);
    }

    private static List<String> parseColumn(String raw, int columnNumber) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        int start = 0;
        int end = rawLines.length;
        while (start < end && rawLines[start].strip().isEmpty()) start++;
        while (end > start && rawLines[end - 1].strip().isEmpty()) end--;

        if (start == end) {
            throw new InvalidContentException("第" + columnNumber + "列第1行为空，请补充内容或删除对应行");
        }

        List<String> result = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            String value = rawLines[i].strip();
            if (value.isEmpty()) {
                throw new InvalidContentException("第" + columnNumber + "列第" + (i - start + 1)
                        + "行为空，请补充内容或删除对应行");
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    /** Stable, ordered fingerprint shared by imports and the migration. */
    public static String fingerprint(List<String> parts) {
        // Include the part count as well as each UTF-8 byte length. Without
        // the count, ["1", "2", "3"] and ["1", "2:3"] could share the
        // same concatenated representation.
        StringBuilder canonical = new StringBuilder().append(parts.size()).append('|');
        for (String part : parts) {
            String value = part == null ? "" : part;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            canonical.append(bytes.length).append(':').append(value);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ParsedColumns(List<List<String>> columns, List<List<String>> rows) {
    }

    public static class InvalidContentException extends IllegalArgumentException {
        public InvalidContentException(String message) {
            super(message);
        }
    }
}
