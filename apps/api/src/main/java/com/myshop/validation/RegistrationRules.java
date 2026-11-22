package com.myshop.validation;

import java.util.regex.Pattern;

/** Canonical registration rules shared by request validation and the auth service. */
public final class RegistrationRules {

    public static final String USERNAME_REGEX = "^[A-Za-z0-9_-]{3,20}$";
    public static final String EMAIL_REGEX =
            "^(?!.*\\.\\.)[A-Za-z0-9](?:[A-Za-z0-9.!#$%&'*+/=?^`{|}~-]{0,62}[A-Za-z0-9])?@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$";
    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";
    public static final String PASSWORD_REGEX = "^[!-~]{6,72}$";

    public static final Pattern USERNAME = Pattern.compile(USERNAME_REGEX);
    public static final Pattern EMAIL = Pattern.compile(EMAIL_REGEX);
    public static final Pattern PHONE = Pattern.compile(PHONE_REGEX);
    public static final Pattern PASSWORD = Pattern.compile(PASSWORD_REGEX);

    private RegistrationRules() {}
}
