package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.Ms3ManualReportPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * MS3 REST client for manual reports ({@code GET /api/agendamiento/reportes/{entidad}}).
 * Forwards the caller's Bearer token with no bypass, enforces bounds/timeouts without redirects,
 * resolves patient, dentist and consultorio names from MS1 catalog, and fails closed (SERVICE_UNAVAILABLE)
 * on any network, protocol, authentication, malformed payload or identity resolution defect.
 */
@Component
public class Ms3ManualReportHttpAdapter implements Ms3ManualReportPort {
    private static final int MAX_FILAS = 500;
    private final String baseUrl;
    private final RestClient client;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public Ms3ManualReportHttpAdapter(
            @Value("${erp.ms3.reports-base-url:https://20-83-242-208.sslip.io/api/agendamiento}") String baseUrl,
            @Value("${erp.ms3.connect-timeout-ms:1000}") int connectTimeout,
            @Value("${erp.ms3.read-timeout-ms:3000}") int readTimeout,
            JdbcTemplate jdbc,
            JsonMapper json) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.jdbc = jdbc;
        this.json = json;
        var httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.clamp(connectTimeout, 100, 10000)))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.clamp(readTimeout, 100, 15000)));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://20-83-242-208.sslip.io/api/agendamiento";
        }
        return url.replaceAll("/+$", "");
    }

    @Override
    public Resultado consultar(String entidad, LocalDate desde, LocalDate hasta, String estado) {
        if (!"citas".equals(entidad) && !"insumos".equals(entidad)) {
            throw new NoDisponible();
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated() || jwt.getToken() == null) {
            throw new NoDisponible();
        }
        String token = jwt.getToken().getTokenValue();
        if (token == null || token.isBlank()) {
            throw new NoDisponible();
        }

        String uri = baseUrl + "/reportes/" + entidad;
        String responseBody;
        try {
            responseBody = client.get()
                    .uri(uriBuilder -> {
                        var b = UriComponentsBuilder.fromUriString(uri);
                        if (desde != null) { b.queryParam("desde", desde.toString()); }
                        if (hasta != null) { b.queryParam("hasta", hasta.toString()); }
                        if (estado != null && !estado.isBlank()) { b.queryParam("estado", estado); }
                        return b.build().toUri();
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new NoDisponible();
        }

        if (responseBody == null || responseBody.isBlank()) {
            throw new NoDisponible();
        }

        JsonNode root;
        try {
            root = json.readTree(responseBody);
        } catch (Exception e) {
            throw new NoDisponible();
        }

        if (!root.path("success").asBoolean(false)) {
            throw new NoDisponible();
        }
        JsonNode data = root.path("data");
        if (!data.isObject() || !entidad.equals(data.path("entidad").asText())) {
            throw new NoDisponible();
        }
        JsonNode totalNode = data.path("total");
        if (!totalNode.isIntegralNumber() || !totalNode.canConvertToLong() || totalNode.longValue() < 0) {
            throw new NoDisponible();
        }
        long total = totalNode.longValue();

        JsonNode filasNode = data.path("filas");
        if (!filasNode.isArray()) {
            throw new NoDisponible();
        }
        int expectedMin = (int) Math.min(total, (long) MAX_FILAS);
        if (filasNode.size() < expectedMin || filasNode.size() > total) {
            throw new NoDisponible();
        }

        List<Map<String, Object>> rawRows = new ArrayList<>(filasNode.size());
        for (JsonNode rowNode : filasNode) {
            validateRow(entidad, rowNode);
            Map<String, Object> map = new LinkedHashMap<>();
            rowNode.properties().forEach(entry -> map.put(entry.getKey(), value(entry.getValue())));
            rawRows.add(map);
        }

        if ("insumos".equals(entidad)) {
            return new Resultado(rawRows, total);
        }

        List<Map<String, Object>> enriched = enrichCitas(rawRows);
        return new Resultado(enriched, total);
    }

    /** Validate fixed S3 row fields, not client-selected columns or display metadata. */
    private static void validateRow(String entidad, JsonNode row) {
        if (!row.isObject()) { throw new NoDisponible(); }
        if ("insumos".equals(entidad)) {
            requireText(row, "insumoId", "nombre");
            requireNumbers(row, "stockActual", "stockMinimo", "salidasPeriodo");
            if (!row.path("bajoMinimo").isBoolean()) { throw new NoDisponible(); }
            nullableText(row, "unidad");
        } else {
            requireText(row, "citaId", "pacienteId", "odontologoId", "fechaHora", "estado");
            requireNumbers(row, "duracionMin");
            nullableText(row, "consultorioId", "tipoTratamiento", "reservadaEn", "nivelRiesgo");
            JsonNode risk = row.path("probabilidadNoShow");
            if (!risk.isMissingNode() && !risk.isNull() && !risk.isNumber()) { throw new NoDisponible(); }
        }
    }

    private static void requireText(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode value = row.path(key);
            if (!value.isTextual() || value.asText().isBlank()) { throw new NoDisponible(); }
        }
    }

    private static void requireNumbers(JsonNode row, String... keys) {
        for (String key : keys) {
            if (!row.path(key).isNumber()) { throw new NoDisponible(); }
        }
    }

    private static void nullableText(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode value = row.path(key);
            if (!value.isMissingNode() && !value.isNull() && !value.isTextual()) { throw new NoDisponible(); }
        }
    }

    private List<Map<String, Object>> enrichCitas(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return rows;
        }

        Set<UUID> pacienteIds = new HashSet<>();
        Set<UUID> odontologoIds = new HashSet<>();
        Set<UUID> consultorioIds = new HashSet<>();

        for (Map<String, Object> row : rows) {
            Object pVal = row.get("pacienteId");
            if (pVal == null || pVal.toString().isBlank()) {
                throw new NoDisponible();
            }
            try {
                pacienteIds.add(UUID.fromString(pVal.toString()));
            } catch (Exception e) {
                throw new NoDisponible();
            }

            Object oVal = row.get("odontologoId");
            if (oVal == null || oVal.toString().isBlank()) {
                throw new NoDisponible();
            }
            try {
                odontologoIds.add(UUID.fromString(oVal.toString()));
            } catch (Exception e) {
                throw new NoDisponible();
            }

            Object cVal = row.get("consultorioId");
            if (cVal != null && !cVal.toString().isBlank()) {
                try {
                    consultorioIds.add(UUID.fromString(cVal.toString()));
                } catch (Exception e) {
                    throw new NoDisponible();
                }
            }
        }

        Map<UUID, String> pacienteNombres = lookupNames("pacientes", "nombres || ' ' || apellidos", pacienteIds);
        if (pacienteNombres.size() < pacienteIds.size()) {
            throw new NoDisponible();
        }

        Map<UUID, String> odontologoNombres = lookupNames("odontologos", "nombres || ' ' || apellidos", odontologoIds);
        if (odontologoNombres.size() < odontologoIds.size()) {
            throw new NoDisponible();
        }

        Map<UUID, String> consultorioNombres = consultorioIds.isEmpty() ? Map.of()
                : lookupNames("consultorios", "nombre", consultorioIds);
        if (consultorioNombres.size() < consultorioIds.size()) {
            throw new NoDisponible();
        }

        List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> map = new LinkedHashMap<>(row);
            UUID pId = UUID.fromString(row.get("pacienteId").toString());
            UUID oId = UUID.fromString(row.get("odontologoId").toString());
            map.put("pacienteNombre", pacienteNombres.get(pId));
            map.put("odontologoNombre", odontologoNombres.get(oId));
            Object cVal = row.get("consultorioId");
            if (cVal != null && !cVal.toString().isBlank()) {
                UUID cId = UUID.fromString(cVal.toString());
                map.put("consultorioNombre", consultorioNombres.get(cId));
            } else {
                map.put("consultorioNombre", null);
            }
            enriched.add(map);
        }
        return enriched;
    }

    private Map<UUID, String> lookupNames(String table, String nameExpr, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, " + nameExpr + " AS nombre FROM " + table + " WHERE id IN (" + placeholders + ")";
        jdbc.query(sql, rs -> {
            result.put(UUID.fromString(rs.getString("id")), rs.getString("nombre"));
        }, ids.toArray());
        return result;
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.numberValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        return node.asText();
    }
}
