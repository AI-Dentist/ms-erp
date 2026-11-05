package com.dentalplatform.erp.configsecurity.infrastructure.security;

import com.dentalplatform.erp.configsecurity.application.port.PasswordVerificationPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class BCryptPasswordVerificationAdapter implements PasswordVerificationPort {
    private final PasswordEncoder encoder;

    BCryptPasswordVerificationAdapter(PasswordEncoder encoder) { this.encoder = encoder; }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return encodedPassword != null && !encodedPassword.isBlank()
                && encoder.matches(rawPassword, encodedPassword);
    }
}
