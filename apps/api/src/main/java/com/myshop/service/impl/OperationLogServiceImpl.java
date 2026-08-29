package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.entity.OperationLog;
import com.myshop.config.ClientIpResolver;
import com.myshop.context.RequestContext;
import com.myshop.repository.OperationLogRepository;
import com.myshop.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogRepository operationLogRepository;
    private final ClientIpResolver clientIpResolver;

    @Override
    public PageResult<?> listLogs(UUID userId, String action, String targetType,
                                   String startDate, String endDate, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        LocalDateTime start = startDate != null ? LocalDate.parse(startDate).atStartOfDay() : null;
        LocalDateTime end = endDate != null ? LocalDate.parse(endDate).atTime(LocalTime.MAX) : null;

        Page<OperationLog> logPage = operationLogRepository.findByFilters(userId, action, targetType, start, end, pageable);
        return PageResult.of(logPage, logPage.getContent());
    }

    @Override
    public void log(UUID userId, String username, String action, String targetType,
                    String targetId, String detail, String ip) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setIpAddress(ip);
        operationLogRepository.save(log);
    }

    @Override
    @Transactional
    public void logCurrentRequest(String action, String targetType, String targetId, String detail) {
        RequestContext.UserInfo userInfo = RequestContext.get();
        UUID userId = userInfo != null ? userInfo.getUserId() : null;
        if (userId == null) {
            throw new IllegalStateException("An authenticated operator is required for an audit log");
        }
        String username = userInfo.getUsername() == null ? "unknown" : userInfo.getUsername();
        String ip = "unknown";
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            ip = clientIpResolver.resolve(servletAttributes.getRequest());
        }
        log(userId, username, action, targetType, targetId, detail, ip);
    }
}
