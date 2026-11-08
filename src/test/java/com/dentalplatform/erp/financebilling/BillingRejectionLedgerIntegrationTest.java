package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.command.ConsultaFinalizadaCommand;
import com.dentalplatform.erp.financebilling.application.usecase.FacturacionSagaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.RechazoFacturacionException;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@WithMockUser(username = "admin", roles = "ADMIN")
class BillingRejectionLedgerIntegrationTest extends IntegrationTestBase {
    private static final String REJECTED = "FACTURACION_CONSULTA_RECHAZADA";
    @Autowired FacturacionSagaUseCase saga;
    @Autowired JsonMapper json;
    @MockitoSpyBean RegistrarAuditoriaUseCase auditoria;

    @Test
    void duplicateRegistrationKeepsOnePublicAuditAndItsOriginalEventId() {
        UUID consulta = UUID.randomUUID();
        UUID closure = UUID.randomUUID();
        var ordersBefore = orderIds();

        saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO");
        var first = rejections(consulta);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().get("entidadAfectada")).isEqualTo("rechazos_facturacion");
        assertThat(first.getFirst().get("entidadId").toString())
                .isNotEqualTo(consulta.toString()).isNotEqualTo(closure.toString());
        assertThat(detail(first.getFirst())).containsEntry("consultaFinalizadaEventId", closure.toString())
                .containsEntry("motivoCodigo", "PAYLOAD_INVALIDO");

        saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO");

        assertThat(rejections(consulta)).isEqualTo(first);
        assertThat(orderIds()).containsExactlyInAnyOrderElementsOf(ordersBefore);
    }

    @Test
    void rejectedClosureReturnsBeforeInvokingSupplierAndCreatesNoOrder() {
        UUID consulta = UUID.randomUUID();
        UUID closure = UUID.randomUUID();
        saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO");
        var before = rejections(consulta);
        var ordersBefore = orderIds();

        assertDoesNotThrow(() -> saga.procesar(consulta, closure, () -> {
            throw new AssertionError("A rejected closure must not be converted or validated");
        }));

        assertThat(rejections(consulta)).isEqualTo(before);
        assertThat(orderIds()).containsExactlyInAnyOrderElementsOf(ordersBefore);
    }

    @Test
    void failedAuditRollsBackRejectionAndRetryRecordsExactlyOnce() {
        UUID consulta = UUID.randomUUID();
        UUID closure = UUID.randomUUID();
        var ordersBefore = orderIds();
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("Synthetic audit outage");
            }
            return invocation.callRealMethod();
        }).when(auditoria).registrar(eq(REJECTED), any(), any(), any());

        assertThatThrownBy(() -> saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(rejections(consulta)).isEmpty();
        assertThat(orderIds()).containsExactlyInAnyOrderElementsOf(ordersBefore);

        // A rolled-back row must not suppress this insert and its operator-visible audit.
        saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO");
        saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO");
        assertThat(rejections(consulta)).hasSize(1);
        assertThat(orderIds()).containsExactlyInAnyOrderElementsOf(ordersBefore);
    }

    @Test
    void commandCannotBillADifferentConsultationThanTheLockedOne() {
        UUID consulta = UUID.randomUUID();
        var ordersBefore = orderIds();
        assertThatThrownBy(() -> saga.procesar(consulta, UUID.randomUUID(), () ->
                new ConsultaFinalizadaCommand(UUID.randomUUID(), null, null, null, null)))
                .isInstanceOfSatisfying(RechazoFacturacionException.class,
                        e -> assertThat(e.codigo()).isEqualTo(RechazoFacturacionException.Codigo.PAYLOAD_INVALIDO));
        assertThat(rejections(consulta)).isEmpty();
        assertThat(orderIds()).containsExactlyInAnyOrderElementsOf(ordersBefore);
    }

    private List<String> orderIds() {
        return graphQl.document("{ listarOrdenesCobro { id } }").execute()
                .path("listarOrdenesCobro[*].id").entityList(String.class).get();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rejections(UUID consulta) {
        return graphQl.document("""
                { listarAuditLogs(limite: 500) { accion entidadAfectada entidadId detalleJson } }
                """).execute().path("listarAuditLogs").entityList(Map.class).get().stream()
                .map(m -> (Map<String, Object>) m)
                .filter(m -> REJECTED.equals(m.get("accion")))
                .filter(m -> consulta.toString().equals(detail(m).get("consultaId"))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> entry) {
        return json.readValue((String) entry.get("detalleJson"), Map.class);
    }
}
