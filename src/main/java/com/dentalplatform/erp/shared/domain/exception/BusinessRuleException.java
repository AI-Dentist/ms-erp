package com.dentalplatform.erp.shared.domain.exception;

/**
 * A domain invariant or business rule was violated. Exposed to clients as a validation error.
 * The optional code names a specific, stable rule so callers never have to read localized message text.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String message) {
        this(message, null);
    }

    public BusinessRuleException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
