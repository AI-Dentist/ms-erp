package com.dentalplatform.erp.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * LOCAL PROFILE ONLY. Authenticates {@code X-Dev-User: <username>} as an ACTIVE row of {@code usuarios} and grants
 * exactly that user's {@code ROLE_<roles.codigo>}. A present but blank, unknown, inactive or repeated header is
 * answered with 401 before anything else runs. Without the header the request continues unauthenticated, so the
 * chain's entry point answers 401. A Bearer token takes precedence over the header (the header is then ignored).
 * Never register this filter outside the {@code local} profile; {@link LocalDevAuthGuard} enforces that at startup.
 */
public class DevUserHeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Dev-User";

    private static final String LOOKUP = """
            SELECT u.id, r.codigo FROM usuarios u JOIN roles r ON r.id = u.rol_id
            WHERE u.username = ? AND u.activo IS TRUE""";

    private final JdbcTemplate jdbc;
    private final RequestAttributeSecurityContextRepository repository = new RequestAttributeSecurityContextRepository();

    public DevUserHeaderAuthenticationFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        String authorization = request.getHeader("Authorization");
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        if (values.isEmpty() || bearer) {
            chain.doFilter(request, response);
            return;
        }
        String username = values.size() == 1 ? values.get(0).trim() : "";
        var rows = username.isEmpty() ? List.<DevUserAuthenticationToken>of() : jdbc.query(LOOKUP,
                (rs, i) -> new DevUserAuthenticationToken(rs.getObject(1, UUID.class), username,
                        List.of(new SimpleGrantedAuthority("ROLE_" + rs.getString(2).toUpperCase(java.util.Locale.ROOT)))),
                username);
        if (rows.size() != 1) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(rows.get(0));
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        repository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }
}
