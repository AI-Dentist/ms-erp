package com.dentalplatform.erp.financebilling.application.port;

import java.util.UUID;

public interface ReceiptStoragePort {
    String guardar(UUID reciboId, byte[] pdf);
}
