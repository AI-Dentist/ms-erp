package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"erp.pagofacil.mock-enabled=false", "erp.pagofacil.webhook-secret=real-disabled-test-secret"})
@WithMockUser(username = "admin", roles = "ADMIN")
class RealPagoFacilUnsupportedIntegrationTest extends IntegrationTestBase {
    @Autowired WebApplicationContext context;

    @Test
    void generationAndCallbackFailClosedWithoutFinancialOrQrWrites() throws Exception {
        int qr = jdbc.queryForObject("SELECT count(*) FROM transacciones_qr", Integer.class);
        int movements = jdbc.queryForObject("SELECT count(*) FROM movimientos_caja", Integer.class);
        graphQl.document("mutation { generarQrPagoFacil(cuotaId: \"" + UUID.randomUUID()
                + "\", ciNitCliente: \"1\", razonSocialCliente: \"Test\") { id } }")
                .execute().errors().expect(e -> e.getMessage().contains("producción no soportada")).verify();
        MockMvcBuilders.webAppContextSetup(context).build().perform(post("/api/v1/webhooks/pagofacil")
                .contentType(MediaType.APPLICATION_JSON).header("X-Local-Mock-Secret", "real-disabled-test-secret")
                .content("{\"transactionId\":\"MOCK-test\",\"amountBs\":1,\"confirmedAt\":\"2026-10-22T10:00:00Z\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("producción no soportada")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transacciones_qr", Integer.class)).isEqualTo(qr);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja", Integer.class)).isEqualTo(movements);
    }
}
