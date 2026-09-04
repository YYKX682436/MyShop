package com.myshop.service;

import com.myshop.model.response.IpLocationResponse;

/** Replaceable boundary for best-effort IP geography in the admin UI. */
public interface IpLocationService {

    IpLocationResponse resolve(String ipAddress);
}
