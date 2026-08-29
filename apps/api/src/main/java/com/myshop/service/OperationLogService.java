package com.myshop.service;

import com.myshop.common.PageResult;

import java.util.UUID;

public interface OperationLogService {

    PageResult<?> listLogs(UUID userId, String action, String targetType,
                            String startDate, String endDate, int page, int pageSize);

    void log(UUID userId, String username, String action, String targetType, String targetId, String detail, String ip);

    /** Writes an audit record using the authenticated request's operator and IP. */
    void logCurrentRequest(String action, String targetType, String targetId, String detail);
}
