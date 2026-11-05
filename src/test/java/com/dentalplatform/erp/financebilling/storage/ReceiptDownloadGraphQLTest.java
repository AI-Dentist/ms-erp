package com.dentalplatform.erp.financebilling.storage;

import com.dentalplatform.erp.financebilling.application.usecase.ConsultarFinanzasUseCase;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.shared.domain.vo.Money;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import static org.mockito.Mockito.*;

class ReceiptDownloadGraphQLTest extends IntegrationTestBase {
    @MockitoBean ConsultarFinanzasUseCase finances;
    @org.springframework.beans.factory.annotation.Autowired
    com.dentalplatform.erp.financebilling.presentation.graphql.ReceiptDownloadGraphQLController downloads;
    private static final String QUERY = """
        { obtenerFlujoCaja(fechaInicio: "2026-01-01T00:00:00Z", fechaFin: "2027-01-01T00:00:00Z") {
          movimientos { recibo { id pdfUrlS3 } }
        } }
        """;
    @BeforeEach void fixture() {
        var movement = MovimientoCaja.crear(UUID.randomUUID(), UUID.randomUUID(), TipoMovimientoCaja.INGRESO,
                "COBRO_CUOTA", MetodoPago.EFECTIVO, Money.of("10"), "Prueba", OffsetDateTime.now());
        var receipt = Recibo.emitir(movement, 1, "123", "Prueba");
        when(finances.obtenerFlujoCaja(any(), any())).thenReturn(new ConsultarFinanzasUseCase.ResumenFlujoCaja(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, List.of(movement)));
        when(finances.obtenerRecibo(movement.id())).thenReturn(Optional.of(receipt));
    }
    @Test @WithMockUser(roles = "RECEPCIONISTA")
    void receptionistCanReadCashflowButNotReceiptDownloadEvenForBareReceipt() {
        graphQl.document(QUERY).execute().errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }
    @Test @WithMockUser(roles = "RECEPCIONISTA")
    void fieldAuthorizationAlsoRejectsDirectConstructedReceipt() {
        var receipt = new Recibo(UUID.randomUUID(), UUID.randomUUID(), "test", "123", "Prueba", "Prueba",
                Money.of("10"), "s3://private-receipts/forged.pdf", false, OffsetDateTime.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> downloads.pdfUrlS3(receipt, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test @WithMockUser(roles = "ADMIN")
    void adminCanResolveMissingArtifactAsNull() {
        graphQl.document(QUERY).execute().path("obtenerFlujoCaja.movimientos[0].recibo.pdfUrlS3").valueIsNull();
    }
    @Test @WithMockUser(roles = "CAJERO")
    void cashierCanResolveMissingArtifactAsNull() {
        graphQl.document(QUERY).execute().path("obtenerFlujoCaja.movimientos[0].recibo.pdfUrlS3").valueIsNull();
    }
    @Test @WithMockUser(roles = "ODONTOLOGO")
    void dentistStillCannotReadCashflow() {
        graphQl.document(QUERY).execute().errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }
}
