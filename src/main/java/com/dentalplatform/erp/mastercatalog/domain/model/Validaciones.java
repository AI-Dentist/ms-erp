package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Field normalization and validation shared by the catalog aggregates.
 */
final class Validaciones {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Validaciones() {
    }

    static String requerido(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("El campo " + field + " es obligatorio");
        }
        return value.trim();
    }

    static String codigo(String value, String field) {
        return requerido(value, field).toUpperCase(Locale.ROOT);
    }

    static String opcional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String emailOpcional(String value) {
        String email = opcional(value);
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw new BusinessRuleException("El email no es válido: " + value);
        }
        return email;
    }
}
