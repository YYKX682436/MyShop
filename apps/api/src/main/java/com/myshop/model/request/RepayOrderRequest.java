package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The only client-controlled hint needed to create the next payment attempt. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RepayOrderRequest(String device) {
}
