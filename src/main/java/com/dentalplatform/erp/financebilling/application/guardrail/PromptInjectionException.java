package com.dentalplatform.erp.financebilling.application.guardrail;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

/** Deliberately excludes the rejected instruction (including possible secrets) from the error. */
public class PromptInjectionException extends BusinessRuleException {
    public PromptInjectionException() {
        super("La instrucción no está permitida por las reglas de seguridad");
    }
}
