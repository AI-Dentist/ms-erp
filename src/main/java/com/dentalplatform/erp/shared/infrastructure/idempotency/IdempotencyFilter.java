package com.dentalplatform.erp.shared.infrastructure.idempotency;

import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.ClaimResult;
import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
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
        try {
            chain.doFilter(request, wrapper);
        } catch (ServletException | IOException | RuntimeException e) {
            store.release(key);
            throw e;
        }
        if (request.isAsyncStarted()) {
            // Keep the key claimed; the async dispatch finishes it with the real body
            request.setAttribute(PENDING_KEY_ATTRIBUTE, key);
            return;
        }
        finish(key, wrapper);
    }

    private void continueAsync(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = (String) request.getAttribute(PENDING_KEY_ATTRIBUTE);
        ContentCachingResponseWrapper wrapper = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        if (key == null || wrapper == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            store.release(key);
            request.removeAttribute(PENDING_KEY_ATTRIBUTE);
            throw e;
        }
        if (!request.isAsyncStarted()) {
            request.removeAttribute(PENDING_KEY_ATTRIBUTE);
            finish(key, wrapper);
        }
    }

    private void finish(String key, ContentCachingResponseWrapper wrapper) throws IOException {
        boolean stored = false;
        try {
            String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (isSuccessful(wrapper.getStatus(), body)) {
                store.complete(key, new StoredResponse(wrapper.getStatus(), wrapper.getContentType(), body));
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(key);
            }
            wrapper.copyBodyToResponse();
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
