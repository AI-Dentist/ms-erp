package com.dentalplatform.erp.configsecurity.application.port;

public interface PasswordHasherPort {

    String hash(String rawPassword);
}
