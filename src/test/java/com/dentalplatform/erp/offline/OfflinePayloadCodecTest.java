package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.offline.application.OfflinePayloadCodec;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt.Type;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.dentalplatform.erp.offline.infrastructure.codec.CanonicalOfflinePayloadCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OfflinePayloadCodecTest {
    private final OfflinePayloadCodec codec = new CanonicalOfflinePayloadCodec();
    private Map<String, Object> patient() {
        return new HashMap<>(Map.of("ciNit", "SYN-1", "nombres", " Ana ",
                "apellidos", "Munoz", "telefonoWhatsapp", "+59170012345"));
    }

    @Test
    void canonicalPatientMatchesPublishedExactUtf8VectorWithoutChangingInput() {
        var payload = patient();
        var before = new HashMap<>(payload);
        String expected = """
                {"payload":{"apellidos":"Munoz","ciNit":"SYN-1","direccion":null,"email":null,"fechaNacimiento":null,"genero":null,"nombres":" Ana ","telefonoWhatsapp":"+59170012345"},"type":"PATIENT_CREATE","v":1}
                """.strip();
        assertThat(codec.canonical(Type.PATIENT_CREATE, payload)).isEqualTo(expected);
        assertThat(codec.hash(Type.PATIENT_CREATE, payload))
                .isEqualTo("1b4c2532beb6b051ee3725959694e4c5c8e375591dc010b91ca3cb939333bb1f");
        assertThat(payload).isEqualTo(before);
    }

    @Test
    void exactDecimalRepresentationsAndDocumentedDefaultsHaveOneHashWithoutRounding() {
        var payload = new HashMap<String, Object>(Map.of("categoria", "INSUMOS", "metodoPago", "EFECTIVO",
                "montoBs", new BigDecimal("45.5000"), "descripcion", "Synthetic",
                "expectedCashflowBalanceBs", new BigDecimal("0.00")));
        String hash = codec.hash(Type.CASH_EXPENSE, payload);
        assertThat(hash).matches("[0-9a-f]{64}");
        payload.put("montoBs", new BigDecimal("45.5"));
        payload.put("requireOpenCashRegister", false);
        assertThat(codec.hash(Type.CASH_EXPENSE, payload)).isEqualTo(hash);
        payload.put("montoBs", new BigDecimal("45.50000000000000001"));
        assertThat(codec.hash(Type.CASH_EXPENSE, payload)).isNotEqualTo(hash);
        assertThat(codec.canonical(Type.CASH_EXPENSE, payload)).contains("\"45.50000000000000001\"");
    }

    @Test
    void uuidDefaultsAndItemOrderAreCanonicalButNeverSortedAway() {
        String patient = "ABCDEFAB-0000-4000-8000-000000000001";
        var first = Map.<String, Object>of("servicioId", "ABCDEFAB-0000-4000-8000-000000000002",
                "cantidad", 1, "expectedUnitPriceBs", new BigDecimal("1.00"));
        var second = Map.<String, Object>of("servicioId", "ABCDEFAB-0000-4000-8000-000000000003",
                "cantidad", 1, "expectedUnitPriceBs", new BigDecimal("2.0"));
        var payload = new HashMap<String, Object>(Map.of("patient", Map.of("id", patient),
                "items", List.of(first, second), "expectedTotalBs", new BigDecimal("3.00")));
        String canonical = codec.canonical(Type.MANUAL_ORDER, payload);
        assertThat(canonical).contains("abcdefab-0000-4000-8000-000000000001",
                "\"descuentoBs\":\"0\"", "\"operationId\":null", "\"piezaDental\":null");
        String hash = codec.hash(Type.MANUAL_ORDER, payload);
        payload.put("items", List.of(second, first));
        assertThat(codec.hash(Type.MANUAL_ORDER, payload)).isNotEqualTo(hash);
    }

    @Test
    void textRemainsExactAndUnknownTransportFieldsCannotEnterAnIntent() {
        var payload = patient();
        payload.put("apellidos", "Muñoz");
        String hash = codec.hash(Type.PATIENT_CREATE, payload);
        assertThat(codec.canonical(Type.PATIENT_CREATE, payload)).contains("Muñoz", " Ana ");
        payload.put("nombres", "Ana");
        assertThat(codec.hash(Type.PATIENT_CREATE, payload)).isNotEqualTo(hash);
        payload.put("sessionId", "not-part-of-the-payload");
        assertThatThrownBy(() -> codec.canonical(Type.PATIENT_CREATE, payload))
                .isInstanceOfSatisfying(OfflineSyncFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("INVALID_INPUT"));
    }
}
