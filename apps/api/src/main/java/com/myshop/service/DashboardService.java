package com.myshop.service;

import com.myshop.model.response.DashboardStatsResponse;
import java.util.List;

public interface DashboardService {

    DashboardStatsResponse getStats();

    List<?> getSalesTrend(String period, String startDate, String endDate);
}
