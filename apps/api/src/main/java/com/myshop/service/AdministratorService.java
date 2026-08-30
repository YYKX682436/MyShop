package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.request.CreateAdministratorRequest;
import com.myshop.model.request.ResetAdministratorPasswordRequest;
import com.myshop.model.request.ToggleAdministratorStatusRequest;
import com.myshop.model.request.UpdateAdministratorRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdministratorResponse;

import java.util.UUID;

public interface AdministratorService {

    PageResult<AdministratorResponse> listAdministrators(String keyword, Integer isDeleted,
                                                          int page, int pageSize);

    AdministratorResponse getAdministrator(UUID id);

    AdministratorResponse createAdministrator(CreateAdministratorRequest request);

    AdministratorResponse updateAdministrator(UUID id, UpdateAdministratorRequest request);

    void toggleAdministrator(UUID id, ToggleAdministratorStatusRequest request);

    void resetAdministratorPassword(UUID id, ResetAdministratorPasswordRequest request);

    PageResult<AdminOperationLogResponse> listOperationLogs(UUID id, int page, int pageSize);
}
