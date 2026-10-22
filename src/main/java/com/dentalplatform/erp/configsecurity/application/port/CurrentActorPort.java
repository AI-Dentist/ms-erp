package com.dentalplatform.erp.configsecurity.application.port;

import java.util.Optional;

/**
 * Who is performing the current request, as seen by the security layer.
 */
public interface CurrentActorPort {

    Actor actual();

    /**
     * @param keycloakId JWT subject, empty for local dev mode
     * @param username   preferred username or dev principal
     * @param ipOrigen   client IP, empty outside an HTTP request (e.g. RabbitMQ listeners)
     */
    record Actor(Optional<String> keycloakId, String username, Optional<String> ipOrigen) {
    }
}
