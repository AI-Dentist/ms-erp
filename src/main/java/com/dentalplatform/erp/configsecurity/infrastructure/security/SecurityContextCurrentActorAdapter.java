package com.dentalplatform.erp.configsecurity.infrastructure.security;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Component
public class SecurityContextCurrentActorAdapter implements CurrentActorPort {

    static final String SYSTEM_USERNAME = "system";

    @Override
    public Actor actual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Optional<String> ip = clientIp();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String username = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"),
                    jwt.getSubject());
            return new Actor(Optional.ofNullable(jwt.getSubject()), username, ip);
        }
        if (auth != null && auth.getName() != null) {
            return new Actor(Optional.empty(), auth.getName(), ip);
        }
        return new Actor(Optional.empty(), SYSTEM_USERNAME, ip);
    }

    private static Optional<String> clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return Optional.empty();
        }
        HttpServletRequest request = servlet.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return Optional.of(forwarded.split(",")[0].trim());
        }
        return Optional.ofNullable(request.getRemoteAddr());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return SYSTEM_USERNAME;
    }
}
