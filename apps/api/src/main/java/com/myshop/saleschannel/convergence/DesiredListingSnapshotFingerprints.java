package com.myshop.saleschannel.convergence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Java implementation of the Agent's UTF-8, recursively sorted JSON fingerprint. */
public final class DesiredListingSnapshotFingerprints {

    private DesiredListingSnapshotFingerprints() { }

    public static String online(ObjectMapper mapper, ListingSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("ONLINE fingerprint requires a snapshot");
        return fingerprint(mapper, snapshot);
    }

    public static String offline(ObjectMapper mapper, String listingKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "desired-listing-v1");
        payload.put("listing_key", listingKey);
        payload.put("desired_state", "OFFLINE");
        return fingerprint(mapper, payload);
    }

    public static String fingerprint(ObjectMapper mapper, Object payload) {
        try {
            ObjectMapper canonicalMapper = mapper.copy()
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
            JsonNode normalized = sortRecursively(canonicalMapper.valueToTree(payload), canonicalMapper);
            byte[] bytes = canonicalMapper.writeValueAsBytes(normalized);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to fingerprint canonical desired listing intent", exception);
        }
    }

    private static JsonNode sortRecursively(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, sortRecursively(value, mapper)));
            return sorted;
        }
        if (node.isArray()) {
            var sorted = mapper.createArrayNode();
            node.forEach(value -> sorted.add(sortRecursively(value, mapper)));
            return sorted;
        }
        return node;
    }
}
