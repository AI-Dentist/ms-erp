package com.dentalplatform.erp.configsecurity.domain.repository;

import com.dentalplatform.erp.configsecurity.domain.model.AuditLog;

import java.util.List;

public interface AuditLogRepository {

    AuditLog guardar(AuditLog log);

    List<AuditLog> ultimos(int limite);
}
