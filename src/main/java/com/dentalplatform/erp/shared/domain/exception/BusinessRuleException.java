package com.dentalplatform.erp.shared.domain.exception;

/**
 * A domain invariant or business rule was violated. Exposed to clients as a validation error.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
