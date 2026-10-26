package com.dentalplatform.erp.shared.infrastructure.idempotency;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Runs the filter through the real Spring GraphQL MVC handler, which completes requests asynchronously.
 */
class IdempotencyGraphQlIntegrationTest extends IntegrationTestBase {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterRegistrationBean<IdempotencyFilter> idempotencyFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .addFilter(idempotencyFilter.getFilter(), "/graphql")
                .build();
    }

    @Test
    void storesAndReplaysTheRealGraphQlBody() throws Exception {
        String key = "it-" + System.nanoTime();
        String body = "{\"query\":\"mutation { actualizarConfiguracionEmpresa(input: {nombreComercial: \\\"Clínica Dental Sonrisa Cruceña\\\", razonSocial: \\\"Sonrisa Cruceña Servicios Odontológicos S.R.L.\\\", nit: \\\"1023456029\\\", direccion: \\\"Av. San Martín #155, Equipetrol Norte, Santa Cruz de la Sierra\\\", telefonoContacto: \\\"+591 3 3412233\\\"}) { nit } }\"}";
        Integer auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);

        String first = execute(key, body);
        String second = execute(key, body);

        assertThat(first).contains("\"nit\":\"1023456029\"").doesNotContain("errors");
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class))
                .as("the mutation must run only once").isEqualTo(auditBefore + 1);
        assertThat(jdbc.queryForObject("SELECT response_payload::text FROM idempotency_keys WHERE idempotency_key = ?",
                String.class, key)).contains("1023456029");
    }

    private String execute(String key, String body) throws Exception {
        MvcResult result = mvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.HEADER, key)
                        .content(body))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse().getContentAsString();
    }
}
