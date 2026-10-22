package com.dentalplatform.erp.shared.infrastructure.idempotency;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.DispatcherType;
import org.springframework.mock.web.MockAsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
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

    @Test
    void storesTheBodyWrittenDuringAsyncDispatchLikeSpringGraphQl() throws Exception {
        MockHttpServletRequest request = post("k-async", BODY);
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // First dispatch: the handler only starts async processing (Spring GraphQL over MVC does this)
        filter.doFilter(request, response, (req, res) -> {
            executions.incrementAndGet();
            req.startAsync(req, res);
        });
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(store.size()).isEqualTo(1);

        // Async dispatch: the result is written to the response captured by the async context
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        ServletResponse asyncResponse = request.getAsyncContext().getResponse();
        filter.doFilter(request, asyncResponse, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write("{\"data\":{\"recibo\":1}}");
        });

        assertThat(response.getContentAsString()).isEqualTo("{\"data\":{\"recibo\":1}}");
        MockHttpServletResponse replay = run(post("k-async", BODY), okChain("{\"data\":{\"recibo\":2}}"));
        assertThat(replay.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo("{\"data\":{\"recibo\":1}}");
        assertThat(executions).hasValue(1);
    }

    @Test
    void releasesKeyWhenAsyncDispatchProducesGraphQlErrors() throws Exception {
        MockHttpServletRequest request = post("k-async-err", BODY);
        request.setAsyncSupported(true);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync(req, res));

        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, request.getAsyncContext().getResponse(),
                (req, res) -> res.getWriter().write("{\"errors\":[{\"message\":\"saldo insuficiente\"}]}"));

        assertThat(store.size()).isZero();
    }

    @Test
    void terminalTimeoutAndErrorKeepUnknownOutcomeClaim() throws Exception {
        for (boolean timeout : new boolean[]{true, false}) {
            String key = "unknown-" + timeout;
            MockHttpServletRequest request = startAsync(key);
            MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
            assertThat(context.getListeners()).hasSize(1);
            for (var listener : context.getListeners()) {
                var event = new AsyncEvent(context, new IllegalStateException("unknown outcome"));
                if (timeout) listener.onTimeout(event);
                else listener.onError(event);
            }
            context.complete();
            MockHttpServletResponse retry = run(post(key, BODY), okChain("{}"));
            assertThat(retry.getStatus()).isEqualTo(409);
            assertThat(retry.getContentAsString()).isEqualTo(
                    "{\"error\":\"Ya existe una solicitud en curso con esta Idempotency-Key\"}");
            assertThat(retry.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        }
        assertThat(executions).hasValue(0);
    }

    @Test
    void terminalCallbacksRacingMutationDoNotEnableDuplicateExecution() throws Exception {
        MockHttpServletRequest request = startAsync("running");
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, context.getResponse(), (req, res) -> {
            for (var listener : context.getListeners()) {
                listener.onTimeout(new AsyncEvent(context));
                listener.onError(new AsyncEvent(context));
            }
            context.complete(); // Servlet completion does not stop this handler.
            try {
                assertThat(run(post("running", BODY), okChain("{}")).getStatus()).isEqualTo(409);
            } catch (Exception e) {
                throw new jakarta.servlet.ServletException(e);
            }
            res.getWriter().write("{\"data\":true}");
        });
        assertThat(run(post("running", BODY), okChain("{}")).getStatus()).isEqualTo(409);
        assertThat(executions).hasValue(0);
    }

    @Test
    void listenerRegistersOnSubsequentAsyncCycle() throws Exception {
        MockHttpServletRequest request = startAsync("cycles");
        MockAsyncContext previous = (MockAsyncContext) request.getAsyncContext();
        assertThat(previous.getListeners()).hasSize(1);
        MockAsyncContext next = new MockAsyncContext(request, previous.getResponse());
        for (var listener : previous.getListeners()) listener.onStartAsync(new AsyncEvent(next));
        assertThat(next.getListeners()).hasSize(1);
        next.complete();
        assertThat(run(post("cycles", BODY), okChain("{}")).getStatus()).isEqualTo(409);
        assertThat(executions).hasValue(0);
    }

    @Test
    void completionKeepsSuccessfulReplayAndLateEventsCannotEraseReplacement() throws Exception {
        MockHttpServletRequest request = startAsync("late");
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        assertThat(context.getListeners()).hasSize(1);
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, context.getResponse(), okChain("{\"data\":1}"));
        context.complete();
        assertThat(run(post("late", BODY), okChain("{}")).getContentAsString()).isEqualTo("{\"data\":1}");
        store.release("late"); // Simulates completed-cache expiry through the public store.
        run(post("late", BODY), okChain("{\"data\":2}"));
        for (var listener : context.getListeners()) {
            listener.onTimeout(new AsyncEvent(context));
            listener.onError(new AsyncEvent(context));
            listener.onComplete(new AsyncEvent(context));
        }
        assertThat(run(post("late", BODY), okChain("{}")).getContentAsString()).isEqualTo("{\"data\":2}");
        assertThat(executions).hasValue(2);
    }

    @Test
    void errorUnwindReleasesClaimAndPreservesOriginalThrowableForBothDispatches() throws Exception {
        for (boolean async : new boolean[]{false, true}) {
            String key = "error-" + async;
            MockHttpServletRequest request = async ? startAsync(key) : post(key, BODY);
            ServletResponse response = new MockHttpServletResponse();
            if (async) {
                response = request.getAsyncContext().getResponse();
                request.setAsyncStarted(false);
                request.setDispatcherType(DispatcherType.ASYNC);
            }
            AssertionError original = new AssertionError("handler failed");
            ServletResponse target = response;
            assertThatThrownBy(() -> filter.doFilter(request, target, (req, res) -> { throw original; }))
                    .isSameAs(original);
            MockHttpServletResponse retry = run(post(key, BODY), okChain("{\"data\":true}"));
            assertThat(retry.getStatus()).isEqualTo(200);
            assertThat(retry.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
            assertThat(retry.getContentAsString()).isEqualTo("{\"data\":true}");
        }
        assertThat(executions).hasValue(2);
    }

    @Test
    void releaseFailureCannotReplaceOriginalError() {
        IdempotencyStore failing = new IdempotencyStore() {
            public ClaimResult claim(String key, String operation, Instant expiresAt) { return ClaimResult.acquired(); }
            public void complete(String key, StoredResponse response) { }
            public void release(String key) { throw new IllegalStateException("release unavailable"); }
        };
        var guarded = new IdempotencyFilter(failing, Duration.ofHours(24), Clock.systemUTC());
        AssertionError original = new AssertionError("mutation failed");
        assertThatThrownBy(() -> guarded.doFilter(post("failure", BODY), new MockHttpServletResponse(),
                (req, res) -> { throw original; })).isSameAs(original);
        assertThat(original.getSuppressed()).hasSize(1);
        assertThat(original.getSuppressed()[0]).hasMessage("release unavailable");
    }

    @Test
    void asyncExceptionUnwindReleasesOnlyOnceDespiteLateEvents() throws Exception {
        MockHttpServletRequest request = startAsync("exception");
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        var original = new jakarta.servlet.ServletException("downstream failed");
        assertThatThrownBy(() -> filter.doFilter(request, context.getResponse(),
                (req, res) -> { throw original; })).isSameAs(original);
        MockHttpServletResponse replacement = run(post("exception", BODY), okChain("{\"data\":3}"));
        assertThat(replacement.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        for (var listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, original));
            listener.onTimeout(new AsyncEvent(context));
        }
        context.complete();
        MockHttpServletResponse replay = run(post("exception", BODY), okChain("{}"));
        assertThat(replay.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo("{\"data\":3}");
        assertThat(executions).hasValue(1);
    }

    private MockHttpServletRequest startAsync(String key) throws Exception {
        MockHttpServletRequest request = post(key, BODY);
        request.setAsyncSupported(true);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync(req, res));
        return request;
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
