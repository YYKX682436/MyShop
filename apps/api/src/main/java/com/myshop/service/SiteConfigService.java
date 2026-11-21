package com.myshop.service;

import java.util.List;
import java.util.Map;
import com.myshop.model.request.UserNumberConfigUpdateRequest;
import com.myshop.model.response.UserNumberConfigResponse;

public interface SiteConfigService {

    Map<String, Object> getPublicConfig();

    /** Checks configured main-site contact details; does not claim external reachability. */
    boolean hasMainSiteContact();

    List<?> getAllConfigs();

    void updateConfigs(List<Map<String, String>> configs);

    void toggleMaintenance(boolean enabled);

    UserNumberConfigResponse getUserNumberConfig();

    void updateUserNumberConfig(UserNumberConfigUpdateRequest request);
}
