package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

public class QrExpiredException extends BusinessRuleException {
    public QrExpiredException() { super("La transacción QR ha expirado"); }
}
