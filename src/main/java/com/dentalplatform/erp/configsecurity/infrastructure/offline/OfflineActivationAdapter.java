package com.dentalplatform.erp.configsecurity.infrastructure.offline;

import com.dentalplatform.erp.configsecurity.application.port.OfflineActivationPort;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OfflineActivationAdapter implements OfflineActivationPort {
    private final Environment environment;

    public OfflineActivationAdapter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Identity authenticatedIdentity() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || jwt.getToken().getIssuer() == null || jwt.getToken().getSubject() == null
                || jwt.getToken().getSubject().isBlank()) {
            throw new AccessDeniedException("Se requiere un JWT validado");
        }
        String issuer = jwt.getToken().getIssuer().toString();
        String configuredIssuer = environment.getProperty("erp.security.local-dev-user.jwt-issuer-uri");
        if (configuredIssuer == null || configuredIssuer.isBlank()) {
            configuredIssuer = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        }
        if (configuredIssuer == null || !issuer.equals(configuredIssuer.trim())) {
            throw new AccessDeniedException("Emisor JWT no disponible");
        }
        return new Identity(issuer, jwt.getToken().getSubject(),
                jwt.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public SignedDocument sign(Map<String, Object> payload) {
        try {
            String key = required("erp.offline.signing.private-key-pkcs8-base64");
            String kid = required("erp.offline.signing.key-id");
            String issuer = required("erp.offline.signing.grant-issuer");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key)));
            if (privateKey.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            var publicKey = factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            var claims = new LinkedHashMap<>(payload);
            claims.put("grantIssuer", issuer);
            var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("erp-offline-grant+jws")).keyID(kid).build();
            var document = new JWSObject(header, new Payload(claims));
            document.sign(new RSASSASigner(privateKey));
            return new SignedDocument(document.serialize(), Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        } catch (Exception ignored) {
            throw new OfflineSigningUnavailableException();
        }
    }

    private String required(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

}
