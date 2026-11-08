package com.dentalplatform.erp.offline.domain;

/** A fixed, classified offline synchronization failure. */
public class OfflineSyncFailure extends RuntimeException {
    private final String code;

    public OfflineSyncFailure(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
