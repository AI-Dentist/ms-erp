package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyFilter;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.dentalplatform.erp.financebilling.infrastructure.persistence.ReciboRepositoryAdapter;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"erp.pagofacil.webhook-secret=local-test-only-secret", "erp.pagofacil.mock-enabled=true"})
@WithMockUser(username = "admin", roles = "ADMIN")
class QrPaymentIntegrationTest extends IntegrationTestBase {
    @Autowired WebApplicationContext context;
    @Autowired FilterRegistrationBean<IdempotencyFilter> idempotency;
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;
    @MockitoSpyBean ReciboRepositoryAdapter receipts;
    MockMvc mvc;

    @BeforeEach
    void web() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(idempotency.getFilter()).build();
    }

    private UUID quota() {
        String order = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) { id } }
                """).variable("input", Map.of("pacienteId", "60000000-0000-0000-0000-000000000001", "items",
                List.of(Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.id").entity(String.class).get();
        return jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ?", UUID.class, UUID.fromString(order));
    }

    private String generate(UUID quota) {
        var response = graphQl.document("""
                mutation($id: UUID!) { generarQrPagoFacil(cuotaId: $id, ciNitCliente: "12345", razonSocialCliente: "Cliente mock") {
                    id cuotaId pagofacilTransactionId montoBs qrImagenBase64 estado fechaExpiracion
                } }
                """).variable("id", quota.toString()).execute();
        response.path("generarQrPagoFacil.montoBs").entity(Double.class).isEqualTo(100.0)
                .path("generarQrPagoFacil.estado").entity(String.class).isEqualTo("GENERADO")
                .path("generarQrPagoFacil.fechaExpiracion").hasValue();
        String image = response.path("generarQrPagoFacil.qrImagenBase64").entity(String.class).get();
        assertThat(java.util.Base64.getDecoder().decode(image)).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
        String transaction = response.path("generarQrPagoFacil.pagofacilTransactionId").entity(String.class).get();
        assertThat(transaction).startsWith("MOCK-");
        return transaction;
    }

    private String body(String transaction, String amount) {
        return "{\"transactionId\":\"" + transaction + "\",\"amountBs\":" + amount
                + ",\"confirmedAt\":\"" + OffsetDateTime.now() + "\"}";
    }

    private String callback(String body, String secret, int status) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/pagofacil").contentType(MediaType.APPLICATION_JSON)
                .header("X-Local-Mock-Secret", secret).header("Idempotency-Key", "same-key-for-all-callbacks")
                .content(body)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }

    private void state(UUID quota, String status, String balance, int count) {
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT saldo_pendiente_bs FROM ordenes_cobro WHERE id = (SELECT orden_cobro_id FROM cuotas_pago WHERE id = ?)", BigDecimal.class, quota)).isEqualByComparingTo(balance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ?", Integer.class, quota)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos r JOIN movimientos_caja m ON r.movimiento_caja_id = m.id WHERE m.cuota_id = ?", Integer.class, quota)).isEqualTo(count);
    }

    @Test
    void storedAmountAtomicConfirmationRetryAndSecretBeforeReplay() throws Exception {
        UUID quota = quota();
        String transaction = generate(quota), body = body(transaction, "100");
        String receipt = callback(body, "local-test-only-secret", 200);
        assertThat(receipt).contains("numeroRecibo", "Cliente mock", "12345");
        state(quota, "PAGADA", "0", 1);
        assertThat(callback(body, "local-test-only-secret", 200)).isEqualTo(receipt);
        state(quota, "PAGADA", "0", 1);
        String rejected = callback(body, "wrong-secret", 401);
        assertThat(rejected).doesNotContain("numeroRecibo", "Cliente mock", "12345");
        callback(body(transaction, "101"), "local-test-only-secret", 400);
        state(quota, "PAGADA", "0", 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ? AND metodo_pago = 'QR_PAGOFACIL' AND usuario_cajero_id = '20000000-0000-0000-0000-000000000001'", Integer.class, quota)).isEqualTo(1);
    }

    @Test
    void mismatchExpiryUnknownAndInvalidTimestampHaveNoEffects() throws Exception {
        UUID quota = quota();
        String transaction = generate(quota);
        callback(body(transaction, "99"), "local-test-only-secret", 400);
        callback(body("MOCK-" + UUID.randomUUID(), "100"), "local-test-only-secret", 404);
        callback(body(transaction, "100").replace(OffsetDateTime.now().getYear() + "-", "2099-"), "local-test-only-secret", 400);
        state(quota, "PENDIENTE", "100", 0);
        jdbc.update("UPDATE transacciones_qr SET fecha_expiracion = now() - interval '1 minute' WHERE pagofacil_transaction_id = ?", transaction);
        callback(body(transaction, "100"), "local-test-only-secret", 410);
        state(quota, "PENDIENTE", "100", 0);
        assertThat(jdbc.queryForObject("SELECT estado FROM transacciones_qr WHERE pagofacil_transaction_id = ?", String.class, transaction)).isEqualTo("GENERADO");
    }

    @Test
    void failedReceiptRollsBackQrAndEveryFinancialWrite() throws Exception {
        UUID quota = quota();
        String transaction = generate(quota);
        doThrow(new BusinessRuleException("Injected receipt failure")).when(receipts).guardar(any());
        callback(body(transaction, "100"), "local-test-only-secret", 400);
        state(quota, "PENDIENTE", "100", 0);
        assertThat(jdbc.queryForObject("SELECT estado FROM transacciones_qr WHERE pagofacil_transaction_id = ?", String.class, transaction)).isEqualTo("GENERADO");
        verifyNoInteractions(pdf, storage);
    }

    @Test
    void paidQuotaCannotGenerateAnotherQrAndClientCannotSupplyAnAmount() throws Exception {
        UUID quota = quota();
        String transaction = generate(quota);
        callback(body(transaction, "100"), "local-test-only-secret", 200);
        graphQl.document("mutation { generarQrPagoFacil(cuotaId: \"" + quota + "\", ciNitCliente: \"1\", razonSocialCliente: \"Test\") { id } }")
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        graphQl.document("mutation { generarQrPagoFacil(cuotaId: \"" + quota + "\", ciNitCliente: \"1\", razonSocialCliente: \"Test\", montoBs: 1) { id } }")
                .execute().errors().expect(e -> e.getMessage().contains("montoBs")).verify();
        state(quota, "PAGADA", "0", 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transacciones_qr WHERE cuota_id = ?", Integer.class, quota)).isEqualTo(1);
    }

    @Test
    void simultaneousCallbacksReturnSameReceiptAndOneMovement() throws Exception {
        UUID quota = quota();
        String body = body(generate(quota), "100");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                return callback(body, "local-test-only-secret", 200);
            })).toList();
            start.countDown();
            assertThat(tasks.get(0).get()).isEqualTo(tasks.get(1).get());
        }
        state(quota, "PAGADA", "0", 1);
    }

    @Test
    void nonDevSimulationRejectedWithoutEffects() {
        UUID quota = quota();
        String transaction = generate(quota);
        graphQl.document("mutation { simularConfirmacionQrDev(pagofacilTransactionId: \"" + transaction + "\") { id } }")
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        state(quota, "PENDIENTE", "100", 0);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ODONTOLOGO")
    void dentistCannotGenerateOrSimulate() {
        graphQl.document("mutation { generarQrPagoFacil(cuotaId: \"" + UUID.randomUUID() + "\", ciNitCliente: \"1\", razonSocialCliente: \"A\") { id } }")
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        graphQl.document("mutation { simularConfirmacionQrDev(pagofacilTransactionId: \"MOCK-test\") { id } }")
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }
}
