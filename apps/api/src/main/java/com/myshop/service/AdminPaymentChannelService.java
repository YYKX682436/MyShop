package com.myshop.service;

import com.myshop.model.response.AdminPaymentChannelResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminPaymentChannelService {

    List<AdminPaymentChannelResponse> listChannels();

    void createChannel(Map<String, Object> request);

    void updateChannel(UUID id, Map<String, Object> request);

    void deleteChannel(UUID id);
}
