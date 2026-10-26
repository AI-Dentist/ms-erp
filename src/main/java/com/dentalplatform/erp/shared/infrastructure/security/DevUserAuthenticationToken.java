package com.dentalplatform.erp.shared.infrastructure.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/**
 * Authentication produced by the local-only {@code X-Dev-User} header. The principal name is the username so audit
 * and idempotency scoping behave as for a real user; the user id and role come from the database, never the header.
 */
public final class DevUserAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID userId;
    private final String username;

    public DevUserAuthenticationToken(UUID userId, String username, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.username = username;
        setAuthenticated(true);
    }

    public UUID userId() {
        return userId;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    @Override
    public String getName() {
        return username;
    }
}
