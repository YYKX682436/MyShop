package com.myshop.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The generated password is returned only by the reset operation response. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminResetPasswordResponse(String temporaryPassword) {
}
