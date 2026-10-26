package com.dentalplatform.erp.shared.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Validates Bearer JWTs against an OIDC issuer that is resolved on first use, never at startup, so the application
 * boots while the identity provider is down. While the issuer is unreachable every token is rejected (the entry point
 * answers 401, never 500); a failed resolution is not cached, so the next request retries once the provider is up.
 * Discovery and JWKS calls are bounded by short timeouts to keep requests from hanging.
 */
public final class LazyIssuerJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(LazyIssuerJwtDecoder.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final String issuer;
    private final Function<String, JwtDecoder> factory;
    private volatile JwtDecoder delegate;

    public LazyIssuerJwtDecoder(String issuer) {
        this(issuer, LazyIssuerJwtDecoder::discover);
    }

    LazyIssuerJwtDecoder(String issuer, Function<String, JwtDecoder> factory) {
        this.issuer = issuer;
        this.factory = factory;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return resolve().decode(token);
    }

    private JwtDecoder resolve() {
        JwtDecoder resolved = delegate;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (delegate == null) {
                try {
                    delegate = factory.apply(issuer);
                } catch (RuntimeException failure) {
                    log.warn("JWT issuer {} is not reachable; Bearer tokens are rejected until it is: {}", issuer,
                            failure.getMessage());
                    // BadJwtException (not a plain JwtException): the filter answers a clean 401 instead of rethrowing a 5xx.
                    throw new BadJwtException("The JWT issuer is not available", failure);
                }
            }
            return delegate;
        }
    }

    private static JwtDecoder discover(String issuer) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        RestTemplate rest = new RestTemplate(requestFactory);
        Map<String, Object> configuration = rest.exchange(issuer + "/.well-known/openid-configuration",
                org.springframework.http.HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
                }).getBody();
        if (configuration == null || !issuer.equals(configuration.get("issuer"))
                || !(configuration.get("jwks_uri") instanceof String jwksUri)) {
            throw new IllegalStateException("The discovery document does not match the configured issuer");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).restOperations(rest).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
