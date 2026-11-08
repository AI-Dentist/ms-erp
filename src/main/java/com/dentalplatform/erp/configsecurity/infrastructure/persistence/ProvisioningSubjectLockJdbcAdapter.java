package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import com.dentalplatform.erp.configsecurity.application.port.ProvisioningSubjectLockPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProvisioningSubjectLockJdbcAdapter implements ProvisioningSubjectLockPort {
    private final JdbcTemplate jdbc;

    ProvisioningSubjectLockJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bloquear(String subject) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, "erp:user-provisioning:" + subject);
    }
}
