package com.dentalplatform.erp.offline.infrastructure.codec;

import com.dentalplatform.erp.offline.application.OfflinePayloadCodec;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt.Type;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Canonicalizes the frozen, already GraphQL-coerced payload without business repricing or mutation. */
@Component
public class CanonicalOfflinePayloadCodec implements OfflinePayloadCodec {
    private enum Kind { TEXT, ENUM, UUID, DATE, DECIMAL, INTEGER, BOOLEAN, REFERENCE, ITEMS }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Kind> PATIENT = Map.of(
            "ciNit", Kind.TEXT, "nombres", Kind.TEXT, "apellidos", Kind.TEXT,
            "fechaNacimiento", Kind.DATE, "genero", Kind.TEXT, "telefonoWhatsapp", Kind.TEXT,
            "email", Kind.TEXT, "direccion", Kind.TEXT);
    private static final Map<String, Kind> EXPENSE = Map.of(
            "categoria", Kind.TEXT, "metodoPago", Kind.ENUM, "montoBs", Kind.DECIMAL,
            "descripcion", Kind.TEXT, "expectedCashflowBalanceBs", Kind.DECIMAL,
            "requireOpenCashRegister", Kind.BOOLEAN);
    private static final Map<String, Kind> CASH = Map.of(
            "quota", Kind.REFERENCE, "expectedOrderState", Kind.ENUM, "expectedQuotaState", Kind.ENUM,
            "expectedOrderBalanceBs", Kind.DECIMAL, "expectedQuotaAmountBs", Kind.DECIMAL,
            "ciNitCliente", Kind.TEXT, "razonSocialCliente", Kind.TEXT, "requireOpenCashRegister", Kind.BOOLEAN);
    private static final Map<String, Kind> ORDER = Map.of(
            "patient", Kind.REFERENCE, "odontologoId", Kind.UUID, "items", Kind.ITEMS,
            "descuentoBs", Kind.DECIMAL, "observaciones", Kind.TEXT, "expectedTotalBs", Kind.DECIMAL);
    private static final Map<String, Kind> REFERENCE = Map.of(
            "id", Kind.UUID, "operationId", Kind.UUID, "installmentNumber", Kind.INTEGER);
    private static final Map<String, Kind> ITEM = Map.of(
            "servicioId", Kind.UUID, "piezaDental", Kind.TEXT, "cantidad", Kind.INTEGER,
            "expectedUnitPriceBs", Kind.DECIMAL);

    @Override
    public String canonical(Type type, Map<String, Object> payload) {
        return new String(bytes(type, payload), StandardCharsets.UTF_8);
    }

    @Override
    public String hash(Type type, Map<String, Object> payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(type, payload)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", ex);
        }
    }

    private byte[] bytes(Type type, Map<String, Object> payload) {
        try {
            if (type == null) throw invalid();
            Object normalized = switch (type) {
                case PATIENT_CREATE -> object(payload, PATIENT,
                        Set.of("ciNit", "nombres", "apellidos", "telefonoWhatsapp"), Map.of());
                case CASH_EXPENSE -> object(payload, EXPENSE,
                        Set.of("categoria", "metodoPago", "montoBs", "descripcion", "expectedCashflowBalanceBs"),
                        Map.of("requireOpenCashRegister", false));
                case CASH_PAYMENT -> object(payload, CASH,
                        Set.of("quota", "expectedOrderState", "expectedQuotaState", "expectedOrderBalanceBs",
                                "expectedQuotaAmountBs", "ciNitCliente", "razonSocialCliente"),
                        Map.of("requireOpenCashRegister", false));
                case MANUAL_ORDER -> object(payload, ORDER, Set.of("patient", "items", "expectedTotalBs"),
                        Map.of("descuentoBs", BigDecimal.ZERO));
            };
            var root = new TreeMap<String, Object>();
            root.put("v", VERSION);
            root.put("type", type.name());
            root.put("payload", normalized);
            // The UTF-8 generator rejects malformed surrogate pairs instead of silently replacing them.
            return JSON.writeValueAsBytes(root);
        } catch (OfflineSyncFailure failure) {
            throw failure;
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private static Map<String, Object> object(Map<?, ?> input, Map<String, Kind> fields,
                                               Set<String> required, Map<String, Object> defaults) {
        if (input == null || !fields.keySet().containsAll(input.keySet())) throw invalid();
        var result = new TreeMap<String, Object>();
        for (var field : fields.entrySet()) {
            Object value = input.get(field.getKey());
            if (value == null) value = defaults.get(field.getKey());
            if (value == null && required.contains(field.getKey())) throw invalid();
            result.put(field.getKey(), value == null ? null : normalize(field.getValue(), value));
        }
        return result;
    }

    private static Object normalize(Kind kind, Object value) {
        return switch (kind) {
            case TEXT -> {
                if (!(value instanceof String)) throw invalid();
                yield value;
            }
            case ENUM -> value instanceof Enum<?> enumeration ? enumeration.name() : text(value);
            case UUID -> UUID.fromString(value.toString()).toString();
            case DATE -> LocalDate.parse(value.toString()).toString();
            case DECIMAL -> {
                BigDecimal decimal = decimal(value).stripTrailingZeros();
                if (decimal.signum() == 0) yield "0";
                // Bound expansion of hostile exponents; financial inputs are much smaller still.
                if (decimal.precision() > 1000 || Math.abs((long) decimal.scale()) > 1000) throw invalid();
                yield decimal.toPlainString();
            }
            case INTEGER -> decimal(value).intValueExact();
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw invalid();
                yield value;
            }
            case REFERENCE -> object(map(value), REFERENCE, Set.of(), Map.of());
            case ITEMS -> {
                if (!(value instanceof List<?> items)) throw invalid();
                yield items.stream().map(item -> object(map(item), ITEM,
                        Set.of("servicioId", "cantidad", "expectedUnitPriceBs"), Map.of())).toList();
            }
        };
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        return map;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Double || value instanceof Float || value instanceof Boolean) throw invalid();
        if (!(value instanceof Number) && !(value instanceof String)) throw invalid();
        return new BigDecimal(value.toString());
    }

    private static OfflineSyncFailure invalid() {
        return new OfflineSyncFailure("INVALID_INPUT");
    }
}
