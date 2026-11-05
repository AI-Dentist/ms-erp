package com.dentalplatform.erp.configsecurity.application.port;

public interface PasswordVerificationPort {
    boolean matches(String rawPassword, String encodedPassword);
}
