package com.myshop.service.system;

import com.myshop.model.response.SystemStatusResponse.ServiceHealth;

/** Health probes used by the admin-only system status endpoint. */
public interface DashboardHealthProbe {

    ServiceHealth probeDatabase();

    ServiceHealth probeWeb();

    ServiceHealth probeCaddy();
}
