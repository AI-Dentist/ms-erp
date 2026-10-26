package com.dentalplatform.erp.shared.infrastructure.idempotency;

import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.ClaimResult;
import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.StoredResponse;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import com.dentalplatform.erp.shared.infrastructure.security.DevUserAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes POST requests carrying an {@code Idempotency-Key} header safe to retry on unstable networks:
 * the first successful response is stored and replayed for later requests with the same key and payload.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    static final int MAX_KEY_LENGTH = 100;
    private static final String PENDING_KEY_ATTRIBUTE = IdempotencyFilter.class.getName() + ".PENDING_KEY";

    private final IdempotencyStore store;
    private final Duration ttl;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyStore store, Duration ttl, Clock clock) {
        this.store = store;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || request.getHeader(HEADER) == null;
    }

    /** Spring GraphQL over MVC answers asynchronously: the body is only written during the async dispatch. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            continueAsync(request, response, chain);
            return;
        }
        String key = request.getHeader(HEADER).trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "La cabecera Idempotency-Key debe tener entre 1 y " + MAX_KEY_LENGTH + " caracteres");
            return;
        }

        CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
        String operation = fingerprint(request.getMethod(), request.getRequestURI(), cachedRequest.body);
        if (request.getUserPrincipal() instanceof JwtAuthenticationToken authentication) {
            // Display usernames are mutable/nonunique. Bind replay to the validated issuer+subject
            // and effective authorities so a later role downgrade cannot replay privileged data.
            var jwt = authentication.getToken();
            String issuer = jwt.getClaimAsString("iss");
            // A token without sub must not share an identity with other subject-less tokens.
            String subject = jwt.getSubject() == null ? jwt.getTokenValue() : jwt.getSubject();
            StringBuilder scope = new StringBuilder(operation);
            for (String value : new String[]{issuer, subject}) {
                scope.append('|').append(value.length()).append(':').append(value);
            }
            authentication.getAuthorities().stream().map(a -> a.getAuthority()).sorted()
                    .forEach(value -> scope.append('|').append(value.length()).append(':').append(value));
            // Keep both the existing 100-character key limit and operation column limit.
            operation = fingerprint(request.getMethod(), request.getRequestURI(),
                    scope.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (request.getUserPrincipal() instanceof DevUserAuthenticationToken authentication) {
            // Local X-Dev-User principals: bind replay to the database user and its role, like JWT subjects.
            StringBuilder scope = new StringBuilder(operation);
            for (String value : new String[]{"dev-user", String.valueOf(authentication.userId()), authentication.getName()}) {
                scope.append('|').append(value.length()).append(':').append(value);
            }
            authentication.getAuthorities().stream().map(a -> a.getAuthority()).sorted()
                    .forEach(value -> scope.append('|').append(value.length()).append(':').append(value));
            operation = fingerprint(request.getMethod(), request.getRequestURI(),
                    scope.toString().getBytes(StandardCharsets.UTF_8));
        }
        ClaimResult claim = store.claim(key, operation, clock.instant().plus(ttl));

        switch (claim.status()) {
            case COMPLETED -> replay(response, claim.response());
            case IN_PROGRESS -> writeError(response, HttpServletResponse.SC_CONFLICT,
                    "Ya existe una solicitud en curso con esta Idempotency-Key");
            case OPERATION_MISMATCH -> writeError(response, 422,
                    "La Idempotency-Key ya fue usada con una solicitud diferente");
            case ACQUIRED -> execute(key, cachedRequest, response, chain);
        }
    }

    private void execute(String key, CachedBodyRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        PendingClaim pending = new PendingClaim(key);
        request.setAttribute(PENDING_KEY_ATTRIBUTE, pending);
        try {
            chain.doFilter(request, wrapper);
        } catch (ServletException | IOException | RuntimeException | Error e) {
            pending.failed(e);
            throw e;
        }
        if (request.isAsyncStarted()) {
            try {
                request.getAsyncContext().addListener(pending);
            } catch (IllegalStateException alreadyCompleted) {
                // Completion raced registration: the outcome is unknown, so retain the claim.
                pending.settled.set(true);
            }
            return;
        }
        finish(pending, wrapper);
    }

    private void continueAsync(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PendingClaim pending = (PendingClaim) request.getAttribute(PENDING_KEY_ATTRIBUTE);
        ContentCachingResponseWrapper wrapper = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        if (pending == null || wrapper == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error e) {
            pending.failed(e);
            throw e;
        }
        if (!request.isAsyncStarted()) {
            finish(pending, wrapper);
        }
    }

    private void finish(PendingClaim pending, ContentCachingResponseWrapper wrapper) throws IOException {
        try {
            if (pending.settled.compareAndSet(false, true)) {
                String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                if (isSuccessful(wrapper.getStatus(), body)) {
                    store.complete(pending.key, new StoredResponse(wrapper.getStatus(), wrapper.getContentType(), body));
                } else {
                    store.release(pending.key);
                }
            }
        } finally {
            wrapper.copyBodyToResponse();
        }
    }

    /**
     * One terminal store action per acquired request, selected before touching persistence. Pending keys
     * never expire: after this action, late events cannot affect a replacement claim. Servlet terminal
     * events alone do not prove that an independent mutation worker has stopped, so they never release.
     */
    private final class PendingClaim implements AsyncListener {
        private final String key;
        private final AtomicBoolean settled = new AtomicBoolean();

        private PendingClaim(String key) {
            this.key = key;
        }

        private void failed(Throwable original) {
            if (settled.compareAndSet(false, true)) {
                try {
                    store.release(key);
                } catch (RuntimeException | Error releaseFailure) {
                    if (releaseFailure != original) original.addSuppressed(releaseFailure);
                }
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            // A completed dispatch has already cached its response; otherwise retain unknown outcome.
            settled.compareAndSet(false, true);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // Do not unlock: the application worker may still be committing a mutation.
        }

        @Override
        public void onError(AsyncEvent event) {
            // Only a failure observed unwinding through the filter can release the claim.
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    /** GraphQL reports failures with HTTP 200 and an "errors" member; those must stay retryable. */
    private static boolean isSuccessful(int status, String body) {
        return status >= 200 && status < 300 && !body.contains("\"errors\"");
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(stored.body());
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    /** Method, path and a SHA-256 prefix of the body; fits the 100-character operation column. */
    static String fingerprint(String method, String uri, byte[] body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
            String prefix = method + " " + (uri.length() > 50 ? uri.substring(0, 50) : uri) + "#";
            return prefix + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the body once so it can be hashed and still be consumed downstream. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
