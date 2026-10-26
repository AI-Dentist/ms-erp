package com.dentalplatform.erp.shared.domain.exception;

import java.util.UUID;

/**
 * A referenced aggregate does not exist.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, UUID id) {
        super(resource + " no encontrado: " + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
