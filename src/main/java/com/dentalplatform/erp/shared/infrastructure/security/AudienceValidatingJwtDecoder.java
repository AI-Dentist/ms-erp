package com.dentalplatform.erp.shared.infrastructure.security;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.text.ParseException;
import java.util.List;
import java.util.Objects;

/** Adds strict audience validation without replacing the delegate's signature, issuer or time checks. */
public final class AudienceValidatingJwtDecoder implements JwtDecoder {
    private final JwtDecoder delegate;
    private final String expectedAudience;

    public AudienceValidatingJwtDecoder(JwtDecoder delegate, String expectedAudience) {
        this.delegate = Objects.requireNonNull(delegate);
        this.expectedAudience = requireAudience(expectedAudience);
    }

    public static String requireAudience(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("ERP JWT audience must not be blank");
        }
        return audience;
    }

    @Override
    public Jwt decode(String token) {
        Jwt verified = delegate.decode(token);
        try {
            // Mapped claims can discard null array elements. Inspect the original signed JSON only after verification.
            var claims = SignedJWT.parse(token).getPayload().toJSONObject();
            Object audience = claims == null ? null : claims.get("aud");
            boolean accepted = audience instanceof String value
                    ? expectedAudience.equals(value)
                    : audience instanceof List<?> values && !values.isEmpty()
                    && values.stream().allMatch(String.class::isInstance) && values.contains(expectedAudience);
            if (!accepted) {
                throw new BadJwtException("The JWT audience is missing or invalid");
            }
            return verified;
        } catch (ParseException malformed) {
            throw new BadJwtException("The JWT audience is missing or invalid", malformed);
        }
    }
}
