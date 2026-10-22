package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class ReceiptDownloadGraphQLController {
    private final ReceiptStoragePort storage;
    public ReceiptDownloadGraphQLController(ReceiptStoragePort storage) { this.storage = storage; }

    // Finance has global ADMIN/CAJERO access, not patient/tenant ownership. No client-supplied reference is accepted.
    @SchemaMapping(typeName = "Recibo", field = "pdfUrlS3")
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public String pdfUrlS3(Recibo receipt) {
        return storage.resolverDescarga(receipt.id(), receipt.pdfUrlS3());
    }
}
