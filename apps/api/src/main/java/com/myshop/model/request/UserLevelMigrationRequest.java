package com.myshop.model.request;

import java.util.UUID;

public record UserLevelMigrationRequest(UUID targetLevelId) {
}
