package com.myshop.service;

import com.myshop.model.request.UserLevelAssignmentRequest;
import com.myshop.model.request.UserLevelMigrationRequest;
import com.myshop.model.request.UserLevelStatusRequest;
import com.myshop.model.request.UserLevelUpsertRequest;
import com.myshop.model.response.UserLevelResponse;

import java.util.List;
import java.util.UUID;

public interface UserLevelService {
    List<UserLevelResponse> list();

    UserLevelResponse create(UserLevelUpsertRequest request);

    UserLevelResponse update(UUID id, UserLevelUpsertRequest request);

    UserLevelResponse setStatus(UUID id, UserLevelStatusRequest request);

    UserLevelResponse setDefault(UUID id);

    void delete(UUID id);

    int migrate(UUID sourceLevelId, UserLevelMigrationRequest request);

    UserLevelResponse assignUser(UUID userId, UserLevelAssignmentRequest request);
}
