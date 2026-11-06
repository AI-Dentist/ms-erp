package com.dentalplatform.erp.configsecurity.application.port;

import java.util.Map;
import java.util.Set;

public interface OfflineActivationPort {
    record Identity(String issuer, String subject, Set<String> authorities) { }
    record SignedDocument(String credencial, String clavePublicaSpki) { }
    final class OfflineSigningUnavailableException extends RuntimeException {
        public OfflineSigningUnavailableException() { super("Configuración de firma offline no disponible"); }
    }

    Identity authenticatedIdentity();
    SignedDocument sign(Map<String, Object> payload);
}
