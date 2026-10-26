package com.dentalplatform.erp.localprofile;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loopback OIDC issuer shaped like the local Keycloak realm ({@code /realms/dental-platform}); signs RS256 tokens. */
final class EmbeddedOidc implements AutoCloseable {

    static final String REALM_PATH = "/realms/dental-platform";

    final RSAKey key = newKey();
    final int port;
    final String issuer;
    private HttpServer server;

    EmbeddedOidc(int port) {
        this.port = port;
        this.issuer = "http://127.0.0.1:" + port + REALM_PATH;
    }

    /** A loopback port nobody listens on right now, to model "Keycloak is down". */
    static int freePort() {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static EmbeddedOidc started() {
        return new EmbeddedOidc(freePort()).start();
    }

    EmbeddedOidc start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", exchange -> {
                String body = exchange.getRequestURI().getPath().endsWith("/jwks")
                        ? new JWKSet(key.toPublicJWK()).toString()
                        : "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer
                        + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Keycloak-like access token: {@code preferred_username} and {@code realm_access.roles}. */
    String token(String subject, String username, String role) {
        return token(key, issuer, subject, username, role, Instant.now().plusSeconds(300));
    }

    String token(RSAKey signingKey, String tokenIssuer, String subject, String username, String role, Instant expiry) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(tokenIssuer).subject(subject)
                    .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(role)))
                    .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiry)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
