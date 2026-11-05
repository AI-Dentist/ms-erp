package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.application.usecase.RepararPdfReciboUseCase;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class ReceiptDownloadGraphQLController {
    private static final Logger log = LoggerFactory.getLogger(ReceiptDownloadGraphQLController.class);
    private final ReceiptStoragePort storage;
    private final RepararPdfReciboUseCase reparar;

    public ReceiptDownloadGraphQLController(ReceiptStoragePort storage, RepararPdfReciboUseCase reparar) {
        this.storage = storage;
        this.reparar = reparar;
    }

    // Existing global ADMIN/CAJERO policy: no client-supplied reference or patient/tenant ownership is introduced.
    @SchemaMapping(typeName = "Recibo", field = "pdfUrlS3")
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public String pdfUrlS3(Recibo receipt, DataFetchingEnvironment environment) {
        String reference = receipt.pdfUrlS3();
        if (reference == null && environment != null
                && environment.getOperationDefinition().getOperation() == OperationDefinition.Operation.QUERY) {
            try {
                reference = reparar.ejecutar(receipt);
            } catch (RuntimeException unavailable) {
                // Optional artifact failures must not undo payment or leak reference/SDK/SQL details.
                log.warn("event=receipt.pdf.repair.pending receiptId={}", receipt.id());
                return null;
            }
        }
        return storage.resolverDescarga(receipt.id(), reference);
    }
}
