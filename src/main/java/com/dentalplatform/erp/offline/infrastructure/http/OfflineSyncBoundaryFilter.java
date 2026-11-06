package com.dentalplatform.erp.offline.infrastructure.http;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Separates new sync operations from the legacy HTTP idempotency cache. */
public final class OfflineSyncBoundaryFilter extends OncePerRequestFilter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SYNC = Set.of("prepararSyncOffline", "confirmarSyncOffline",
            "sincronizarOperacionOffline", "estadoOperacionOffline");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var wrapped = new CachedBodyRequest(request);
        Roots roots = inspect(wrapped.body);
        if (roots != null && roots.sync) {
            String code = request.getHeader("Idempotency-Key") != null
                    ? "OFFLINE_LEGACY_KEY_NOT_ALLOWED"
                    : roots.business > 1 || roots.invalid ? "OFFLINE_MIXED_OPERATION" : null;
            if (code != null) {
                response.setStatus(400);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"code\":\"" + code + "\"}");
                return;
            }
        }
        chain.doFilter(wrapped, response);
    }

    private static Roots inspect(byte[] body) {
        try {
            JsonNode json = JSON.readTree(body);
            JsonNode query = json.path("query");
            if (!query.isString()) return null;
            Document document = new Parser().parseDocument(query.asString());
            var operations = document.getDefinitionsOfType(OperationDefinition.class);
            JsonNode name = json.path("operationName");
            OperationDefinition selected;
            if (name.isMissingNode() || name.isNull()) {
                if (operations.size() != 1) return null;
                selected = operations.get(0);
            } else {
                if (!name.isString()) return null;
                var matches = operations.stream().filter(op -> name.asString().equals(op.getName())).toList();
                if (matches.size() != 1) return null;
                selected = matches.get(0);
            }
            Map<String, FragmentDefinition> fragments = new HashMap<>();
            for (var fragment : document.getDefinitionsOfType(FragmentDefinition.class)) {
                if (fragments.put(fragment.getName(), fragment) != null) return null;
            }
            Roots roots = new Roots();
            roots.sync = containsSync(selected.getSelectionSet(), fragments);
            collect(selected.getSelectionSet(), fragments, new HashSet<>(), roots, 0);
            return roots;
        } catch (RuntimeException e) {
            // Invalid legacy JSON/GraphQL remains the responsibility of the existing handler.
            return null;
        }
    }

    private static boolean containsSync(SelectionSet initial, Map<String, FragmentDefinition> fragments) {
        var pending = new ArrayDeque<SelectionSet>();
        var seen = new HashSet<String>();
        if (initial != null) pending.add(initial);
        while (!pending.isEmpty()) {
            for (var selection : pending.remove().getSelections()) {
                if (selection instanceof Field field && SYNC.contains(field.getName())) return true;
                if (selection instanceof InlineFragment inline && inline.getSelectionSet() != null) {
                    pending.add(inline.getSelectionSet());
                } else if (selection instanceof FragmentSpread spread && seen.add(spread.getName())) {
                    var fragment = fragments.get(spread.getName());
                    if (fragment != null && fragment.getSelectionSet() != null) pending.add(fragment.getSelectionSet());
                }
            }
        }
        return false;
    }

    private static void collect(SelectionSet selections, Map<String, FragmentDefinition> fragments,
                                Set<String> visiting, Roots roots, int depth) {
        if (depth > 128) { roots.invalid = true; return; }
        if (selections == null) return;
        for (var selection : selections.getSelections()) {
            if (++roots.visited > 10000) { roots.invalid = true; return; }
            if (selection instanceof Field field) {
                if (!"__typename".equals(field.getName())) roots.business++;
                if (SYNC.contains(field.getName())) roots.sync = true;
            } else if (selection instanceof InlineFragment inline) {
                collect(inline.getSelectionSet(), fragments, visiting, roots, depth + 1);
            } else if (selection instanceof FragmentSpread spread) {
                var fragment = fragments.get(spread.getName());
                if (fragment == null || !visiting.add(spread.getName())) {
                    roots.invalid = true;
                } else {
                    collect(fragment.getSelectionSet(), fragments, visiting, roots, depth + 1);
                    visiting.remove(spread.getName());
                }
            }
        }
    }

    private static final class Roots {
        int business;
        int visited;
        boolean sync;
        boolean invalid;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            body = request.getInputStream().readAllBytes();
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                private ReadListener listener;
                private boolean notified;
                private boolean inCallback;
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener value) {
                    if (value == null || listener != null || !isAsyncStarted()) throw new IllegalStateException();
                    listener = value;
                    getAsyncContext().start(() -> {
                        try {
                            inCallback = true;
                            if (!isFinished()) listener.onDataAvailable();
                            inCallback = false;
                            notifyFinished();
                        } catch (IOException | RuntimeException e) { listener.onError(e); }
                    });
                }
                private void notifyFinished() throws IOException {
                    if (listener != null && !inCallback && isFinished() && !notified) {
                        notified = true;
                        listener.onAllDataRead();
                    }
                }
                @Override public int read() throws IOException {
                    int result = input.read();
                    notifyFinished();
                    return result;
                }
            };
        }
        @Override public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
