package com.myshop.supplier;

import com.myshop.entity.SupplierProductMapping;
import java.time.LocalDateTime;

/** Buyer quantity is stock divided by the provider delivery multiple, then capped by buyer-count limits. */
public final class SupplierStockRules {
    public static final int FRESH_MINUTES = 30;
    private static final int FUTURE_TOLERANCE_MINUTES = 1;

    private SupplierStockRules() { }

    public record Availability(Long quantity, String status, int minQuantity, int maxQuantity, String errorCode) { }

    public static Availability assess(SupplierProductMapping mapping, LocalDateTime now) {
        if (mapping == null) return result(null, "UNMAPPED", 0, 0, null);
        int min = mapping.getMinQuantity();
        int max = mapping.getMaxQuantity();
        String syncError = mapping.getSyncError();

        if (isLocalError(syncError)) return result(0L, "LOCAL_UNAVAILABLE", min, max, syncError);
        if (syncError != null) return result(null, "SYNC_FAILED", min, max, syncError);
        if (mapping.getLastSyncedAt() == null
                || mapping.getLastSyncedAt().isBefore(now.minusMinutes(FRESH_MINUTES))
                || mapping.getLastSyncedAt().isAfter(now.plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
            return result(null, "STALE", min, max, null);
        }
        if (mapping.getGoodsStatus() != 1 || mapping.getSkuStatus() != 1) {
            return result(0L, "UPSTREAM_UNAVAILABLE", min, max, null);
        }
        int multiple = mapping.getMultiple();
        long rawStock = mapping.getStock();
        if (multiple < 1 || min < 0 || max < 0 || (max > 0 && min > max) || rawStock < 0) {
            return result(null, "INVALID_DATA", min, max, "DATA");
        }
        if (rawStock == 0) return result(0L, "OUT_OF_STOCK", min, max, null);
        if (rawStock < multiple) return result(0L, "MULTIPLE_SHORTAGE", min, max, null);

        long quantity = rawStock / multiple;
        if (max > 0) quantity = Math.min(quantity, max);
        if (min > 0 && quantity < min) return result(0L, "BELOW_MINIMUM", min, max, null);
        if (quantity < 1) return result(0L, "OUT_OF_STOCK", min, max, null);
        return result(quantity, "AVAILABLE", min, max, null);
    }

    public static Availability accountUnavailable(SupplierProductMapping mapping) {
        return result(null, "ACCOUNT_UNAVAILABLE", mapping.getMinQuantity(), mapping.getMaxQuantity(), null);
    }

    public static Availability unsupportedInputs(SupplierProductMapping mapping) {
        return result(0L, "INPUT_UNSUPPORTED", mapping.getMinQuantity(), mapping.getMaxQuantity(), null);
    }

    private static boolean isLocalError(String error) {
        return "LOCAL_DELETED".equals(error) || "LOCAL_CHANGED".equals(error)
                || "LOCAL_SPEC_MISSING".equals(error);
    }

    private static Availability result(Long quantity, String status, int min, int max, String error) {
        return new Availability(quantity, status, min, max, error);
    }
}
