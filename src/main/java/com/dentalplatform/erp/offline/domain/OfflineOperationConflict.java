package com.dentalplatform.erp.offline.domain;

/** A business rejection, recorded only after its execution transaction has rolled back. */
public class OfflineOperationConflict extends RuntimeException {
    private final OfflineOperationReceipt.State state;
    public OfflineOperationConflict(String reason) {
        this(reason, OfflineOperationReceipt.State.CONFLICT);
    }
    public OfflineOperationConflict(String reason, OfflineOperationReceipt.State state) {
        super(reason);
        this.state = state;
    }
    public String reason() { return getMessage(); }
    public OfflineOperationReceipt.State state() { return state; }
}
