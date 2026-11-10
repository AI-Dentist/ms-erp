package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@WithMockUser(roles = "ADMIN")
class GastoPagadoCobroGraphQLTest extends IntegrationTestBase {
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;

    @Test
    void actualCashSettlementIsVisibleThroughReadQuery() {
        UUID patient = UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes(id,ci_nit,nombres,apellidos,telefono_whatsapp) VALUES (?,?,?,?,?)",
                patient, patient.toString().substring(0, 30), "Synthetic", "Patient", "0");
        String quota = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
                  cuotas { id montoCuotaBs }
                } }
                """).variable("input", Map.of("pacienteId", patient.toString(), "items", List.of(
                        Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.cuotas[0].id").entity(String.class).get();
        Double amount = graphQl.document("""
                mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { montoTotalBs } }
                """).variable("input", Map.of("cuotaId", quota, "usuarioCajeroId", "20000000-0000-0000-0000-000000000002",
                        "ciNitCliente", "0", "razonSocialCliente", "Synthetic"))
                .execute().path("cobrarCuotaEnEfectivo.montoTotalBs").entity(Double.class).get();
        graphQl.document("query($id: UUID!) { obtenerGastoPagadoPaciente(pacienteId: $id) { completo totalPagadoBs } }")
                .variable("id", patient.toString()).execute()
                .path("obtenerGastoPagadoPaciente.completo").entity(Boolean.class).isEqualTo(true)
                .path("obtenerGastoPagadoPaciente.totalPagadoBs").entity(Double.class).isEqualTo(amount);
    }

    @Test
    void queryDoesNotAdvanceReceiptCounterBetweenConsecutivePublicCashSettlements() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes(id,ci_nit,nombres,apellidos,telefono_whatsapp) VALUES (?,?,?,?,?)",
                p1, p1.toString().substring(0, 30), "Synthetic1", "Patient1", "1");
        jdbc.update("INSERT INTO pacientes(id,ci_nit,nombres,apellidos,telefono_whatsapp) VALUES (?,?,?,?,?)",
                p2, p2.toString().substring(0, 30), "Synthetic2", "Patient2", "2");

        String q1 = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
                  cuotas { id }
                } }
                """).variable("input", Map.of("pacienteId", p1.toString(), "items", List.of(
                        Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.cuotas[0].id").entity(String.class).get();

        String q2 = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
                  cuotas { id }
                } }
                """).variable("input", Map.of("pacienteId", p2.toString(), "items", List.of(
                        Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.cuotas[0].id").entity(String.class).get();

        String rec1 = graphQl.document("""
                mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { numeroRecibo } }
                """).variable("input", Map.of("cuotaId", q1, "usuarioCajeroId", "20000000-0000-0000-0000-000000000002",
                        "ciNitCliente", "0", "razonSocialCliente", "Synthetic"))
                .execute().path("cobrarCuotaEnEfectivo.numeroRecibo").entity(String.class).get();

        graphQl.document("query($id: UUID!) { obtenerGastoPagadoPaciente(pacienteId: $id) { completo totalPagadoBs } }")
                .variable("id", p1.toString()).execute()
                .path("obtenerGastoPagadoPaciente.completo").entity(Boolean.class).isEqualTo(true);
        graphQl.document("query($id: UUID!) { obtenerGastoPagadoPaciente(pacienteId: $id) { completo totalPagadoBs } }")
                .variable("id", p2.toString()).execute()
                .path("obtenerGastoPagadoPaciente.completo").entity(Boolean.class).isEqualTo(true)
                .path("obtenerGastoPagadoPaciente.totalPagadoBs").entity(Double.class).isEqualTo(0.0);

        String rec2 = graphQl.document("""
                mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { numeroRecibo } }
                """).variable("input", Map.of("cuotaId", q2, "usuarioCajeroId", "20000000-0000-0000-0000-000000000002",
                        "ciNitCliente", "0", "razonSocialCliente", "Synthetic"))
                .execute().path("cobrarCuotaEnEfectivo.numeroRecibo").entity(String.class).get();

        long num1 = Long.parseLong(rec1.substring(rec1.lastIndexOf('-') + 1));
        long num2 = Long.parseLong(rec2.substring(rec2.lastIndexOf('-') + 1));
        assertThat(num2).isEqualTo(num1 + 1);
    }
}
