package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.request.AdminAdjustBalanceRequest;
import com.myshop.model.request.AdminResetPasswordRequest;
import com.myshop.model.request.AdminToggleUserStatusRequest;
import com.myshop.model.request.AdminUpdateUserProfileRequest;
import com.myshop.model.response.AdminOperationLogResponse;
import com.myshop.model.response.AdminUserDetailResponse;
import com.myshop.model.response.AdminUserListResponse;
import com.myshop.model.response.AdminUserOrderResponse;
import com.myshop.model.response.BalanceTransactionResponse;

import java.util.UUID;

public interface AdminUserService {

    PageResult<AdminUserListResponse> listUsers(String keyword, Integer isDeleted, int page, int pageSize);

    PageResult<AdminUserListResponse> listUsers(String keyword, Integer isDeleted, UUID userLevelId,
                                                int page, int pageSize);

    AdminUserDetailResponse getUserDetail(UUID id);

    PageResult<AdminUserOrderResponse> listUserOrders(UUID id, int page, int pageSize);

    PageResult<BalanceTransactionResponse> listBalanceTransactions(UUID id, int page, int pageSize);

    PageResult<AdminOperationLogResponse> listUserOperationLogs(UUID id, int page, int pageSize);

    AdminUserDetailResponse updateProfile(UUID id, AdminUpdateUserProfileRequest request);

    BalanceTransactionResponse adjustBalance(UUID id, AdminAdjustBalanceRequest request);

    void toggleUser(UUID id, AdminToggleUserStatusRequest request);

    void resetPassword(UUID id, AdminResetPasswordRequest request);
}
