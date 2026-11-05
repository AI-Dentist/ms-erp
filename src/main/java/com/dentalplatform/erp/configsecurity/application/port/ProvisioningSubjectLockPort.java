package com.dentalplatform.erp.configsecurity.application.port;

/** Serializes new identity claims until the caller's transaction completes. */
public interface ProvisioningSubjectLockPort {
    void bloquear(String subject);
}
