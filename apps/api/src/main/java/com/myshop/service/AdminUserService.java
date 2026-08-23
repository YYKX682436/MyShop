package com.myshop.service;

import com.myshop.common.PageResult;

import java.util.UUID;

public interface AdminUserService {

    PageResult<?> listUsers(String keyword, int page, int pageSize);

    void toggleUser(UUID id, int isDeleted);

    void resetPassword(UUID id, String newPassword);
}
