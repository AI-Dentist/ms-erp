package com.dentalplatform.erp.shared.infrastructure.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyFilterTest {

    private static final String BODY = "{\"query\":\"mutation { cobrarCuotaEnEfectivo }\"}";

    private InMemoryIdempotencyStore store;
    private IdempotencyFilter filter;
    private AtomicInteger executions;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
        filter = new IdempotencyFilter(store, Duration.ofHours(24), Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        executions = new AtomicInteger();
    }

    @Test
    void passesThroughWhenHeaderIsAbsent() throws Exception {
        MockHttpServletResponse response = run(post(null, BODY), okChain("{\"data\":{}}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(executions).hasValue(1);
        assertThat(store.size()).isZero();
    }

    @Test
    void passesThroughForGetRequestsEvenWithHeader() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/reports/export/xlsx");
        get.addHeader(IdempotencyFilter.HEADER, "k-1");

        run(get, okChain("bin"));

        assertThat(executions).hasValue(1);
        assertThat(store.size()).isZero();
    }

    @Test
    void storesFirstSuccessfulResponseAndReplaysItWithoutReExecuting() throws Exception {
        MockHttpServletResponse first = run(post("k-1", BODY), okChain("{\"data\":{\"ok\":1}}"));
        MockHttpServletResponse second = run(post("k-1", BODY), okChain("{\"data\":{\"ok\":2}}"));

        assertThat(executions).hasValue(1);
        assertThat(first.getContentAsString()).isEqualTo("{\"data\":{\"ok\":1}}");
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getContentAsString()).isEqualTo("{\"data\":{\"ok\":1}}");
        assertThat(second.getContentType()).startsWith("application/json");
        assertThat(second.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(first.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
    }

    @Test
    void rejectsKeyReusedWithDifferentPayload() throws Exception {
        run(post("k-1", BODY), okChain("{\"data\":{}}"));

        MockHttpServletResponse response = run(post("k-1", "{\"query\":\"other\"}"), okChain("{\"data\":{}}"));

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("Idempotency-Key");
        assertThat(executions).hasValue(1);
    }

    @Test
    void returnsConflictWhileTheSameKeyIsStillProcessing() throws Exception {
        store.claim("k-1", IdempotencyFilter.fingerprint("POST", "/graphql", BODY.getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2026-10-22T12:00:00Z"));

        MockHttpServletResponse response = run(post("k-1", BODY), okChain("{\"data\":{}}"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(executions).hasValue(0);
    }

    @Test
    void releasesKeyWhenGraphQlResponseContainsErrorsSoClientCanRetry() throws Exception {
        run(post("k-1", BODY), okChain("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}"));
        run(post("k-1", BODY), okChain("{\"data\":{\"ok\":true}}"));

        assertThat(executions).hasValue(2);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void releasesKeyWhenResponseIsNotSuccessful() throws Exception {
        run(post("k-1", BODY), (req, res) -> {
            executions.incrementAndGet();
            ((HttpServletResponse) res).setStatus(500);
        });

        assertThat(store.size()).isZero();
    }

    @Test
    void releasesKeyAndPropagatesWhenDownstreamThrows() {
        assertThatThrownBy(() -> run(post("k-1", BODY), (req, res) -> {
            throw new IllegalStateException("db down");
        })).hasCauseInstanceOf(IllegalStateException.class);

        assertThat(store.size()).isZero();
    }

    @Test
    void rejectsKeysLongerThanTheColumn() throws Exception {
        MockHttpServletResponse response = run(post("x".repeat(101), BODY), okChain("{}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(executions).hasValue(0);
    }

    @Test
    void downstreamCanStillReadTheRequestBody() throws Exception {
        StringBuilder seen = new StringBuilder();
        run(post("k-1", BODY), (req, res) -> {
            seen.append(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            res.getWriter().write("{\"data\":{}}");
        });

        assertThat(seen.toString()).isEqualTo(BODY);
    }

    private MockHttpServletRequest post(String key, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            request.addHeader(IdempotencyFilter.HEADER, key);
        }
        return request;
    }

    private FilterChain okChain(String body) {
        return (req, res) -> {
            executions.incrementAndGet();
            res.setContentType("application/json");
            res.getWriter().write(body);
        };
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.doFilter(request, response, chain);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return response;
    }
}
