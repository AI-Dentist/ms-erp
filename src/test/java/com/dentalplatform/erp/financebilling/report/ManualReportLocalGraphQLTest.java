package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@WithMockUser(username = "manual-admin", roles = "ADMIN")
class ManualReportLocalGraphQLTest extends IntegrationTestBase {
    static final String GENERATE = """
            mutation($i: ReporteManualInput!) { generarReporteManual(input: $i) {
              reporteId titulo columnas filas filasJson filasTruncadas tipoGraficoSugerido resumenAnalitico
              consultaSqlEjecutada sqlGenerado urlDescargaPdf urlDescargaExcel
            } }
            """;
    static final String FROM = "2150-02-01T00:00:00-04:00", TO = "2150-02-28T23:59:59-04:00";
    @Autowired WebApplicationContext web;
    @Autowired JsonMapper json;
    MockMvc mvc;

    @BeforeEach void prepare() { mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build(); }

    Map<String,Object> input(String entity, String... columns) {
        return new LinkedHashMap<>(Map.of("entidad", entity, "columnas", List.of(columns)));
    }
    Map<String,Object> dated(String entity, String... columns) {
        var i = input(entity, columns); i.put("fechaInicio", FROM); i.put("fechaFin", TO); return i;
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> generate(Map<String,Object> input) {
        restoreCaller();
        return graphQl.document(GENERATE).variable("i", input).execute()
                .path("generarReporteManual").entity(Map.class).get();
    }

    @Test void metadataIsAnAdminServerWhitelist() {
        var r = graphQl.document("{ listarEntidadesReporte { clave nombre origen columnas { clave etiqueta tipo } filtrosEstado filtroFecha } }").execute();
        r.path("listarEntidadesReporte[*].clave").entityList(String.class)
                .containsExactly("citas", "pacientes", "cuotas", "pagos", "egresos", "servicios", "insumos");
        r.path("listarEntidadesReporte[*].columnas[*].tipo").entityList(String.class).satisfies(types ->
                assertThat(types).allMatch(Set.of("TEXTO","NUMERO","MONEDA_BS","FECHA","FECHA_HORA","BOOLEANO")::contains));
        r.path("listarEntidadesReporte[*].columnas[*].clave").entityList(String.class).satisfies(keys ->
                assertThat(keys).doesNotContain("pacienteId", "odontologoId", "consultorioId", "password", "pdfUrlS3"));
    }

    @ParameterizedTest @ValueSource(strings={"pacientes","cuotas","pagos","egresos","servicios"})
    void everyLocalEntitySelectsOrderedColumnsAndExportsRealFiles(String entity) throws Exception {
        UUID patient = fixture();
        Map<String,Object> request = switch(entity) {
            case "pacientes" -> dated(entity, "ciNit", "nombres");
            case "cuotas" -> dated(entity, "montoCuotaBs", "numeroCuota");
            case "pagos" -> dated(entity, "montoBs", "metodoPago", "montoNominal");
            case "egresos" -> dated(entity, "descripcion", "montoBs");
            default -> { var i=input(entity,"precioBaseBs","codigo"); i.put("estado","INACTIVO"); yield i; }
        };
        Object before = businessSnapshot(patient);
        var report = generate(request);
        assertThat(report.get("tipoGraficoSugerido")).isEqualTo("TABLA");
        assertThat(report.get("titulo").toString()).isNotBlank();
        assertThat(report.get("filasTruncadas")).isEqualTo(false);
        assertThat(report.get("consultaSqlEjecutada")).isEqualTo(report.get("sqlGenerado"));
        assertThat(report.get("consultaSqlEjecutada").toString()).contains("?").doesNotContain("2150-02");
        assertThat(json.readValue(report.get("filasJson").toString(), List.class)).isEqualTo(report.get("filas"));
        var labels = (List<?>) report.get("columnas");
        assertThat(labels).hasSize(((List<?>) request.get("columnas")).size());
        assertThat((List<?>)report.get("filas")).isNotEmpty();
        String id = report.get("reporteId").toString();
        byte[] xlsx = mvc.perform(get("/api/v1/reports/export/"+id+"/xlsx")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx).startsWith((byte)'P',(byte)'K');
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var row=book.getSheetAt(0).getRow(10);
            for(int c=0;c<labels.size();c++) assertThat(row.getCell(c).getStringCellValue()).isEqualTo(labels.get(c));
        }
        byte[] pdf = mvc.perform(get("/api/v1/reports/export/"+id+"/pdf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf,0,5,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(get("/api/v1/reports/export/"+id+"/pdf").with(user("other-admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reports/export/"+id+"/pdf").with(user("cashier").roles("CAJERO")))
                .andExpect(status().isForbidden());
        assertThat(businessSnapshot(patient)).isEqualTo(before);
        if(entity.equals("pagos")) {
            assertThat(report.get("resumenAnalitico").toString()).contains("QR", "nominal", "/1000");
            assertThat(labels.toString()).contains("nominal");
        }
    }

    @Test @SuppressWarnings("unchecked") void everyCatalogColumnOfEveryLocalEntityRuns() {
        restoreCaller();
        var entities = graphQl.document("{ listarEntidadesReporte { clave origen columnas { clave } } }").execute()
                .path("listarEntidadesReporte").entityList(Map.class).get();
        for (var entity : entities) {
            if (!"MS1".equals(entity.get("origen"))) continue;
            var keys = ((List<Map<String,Object>>) entity.get("columnas")).stream().map(c -> c.get("clave").toString()).toArray(String[]::new);
            var report = generate(input(entity.get("clave").toString(), keys));
            assertThat((List<?>) report.get("columnas")).as(entity.get("clave").toString()).hasSize(keys.length);
        }
    }

    @Test void eachInvalidRequestIsBadRequestAndDoesNotEvictAStoredReport() throws Exception {
        fixture();
        String first = generate(input("servicios","codigo")).get("reporteId").toString();
        for(int n=1;n<64;n++) generate(input("servicios","codigo"));
        var bad = new ArrayList<Map<String,Object>>();
        bad.add(input("pacientes; DROP TABLE pacientes", "nombres"));
        bad.add(input("pacientes"));
        bad.add(input("pacientes","nombres; DELETE FROM pacientes"));
        bad.add(input("pacientes","nombres","nombres"));
        var state=input("cuotas","estado"); state.put("estado","PAGADA' OR TRUE --"); bad.add(state);
        bad.add(dated("servicios","codigo"));
        var reversed=dated("pacientes","id"); reversed.put("fechaInicio","2151-01-01T00:00:00Z"); bad.add(reversed);
        var longPeriod=dated("pacientes","id"); longPeriod.put("fechaFin","2152-01-01T00:00:00Z"); bad.add(longPeriod);
        for(var i:bad) {
            restoreCaller();
            graphQl.document(GENERATE).variable("i",i).execute().errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            });
            mvc.perform(get("/api/v1/reports/export/"+first+"/xlsx")).andExpect(status().isOk());
        }
    }

    @Test void emptyAndFiveHundredBoundariesHaveTruthfulTruncation() {
        var none=dated("pacientes","nombres"); none.put("fechaInicio","2160-01-01T00:00:00Z"); none.put("fechaFin","2160-01-02T00:00:00Z");
        assertThat((List<?>)generate(none).get("filas")).isEmpty();
        for(int n=0;n<500;n++) patient("2160-01-01T12:00:00Z");
        var full=generate(none); assertThat((List<?>)full.get("filas")).hasSize(500);
        assertThat(full.get("filasTruncadas")).isEqualTo(false);
        patient("2160-01-01T12:00:00Z");
        var capped=generate(none); assertThat((List<?>)capped.get("filas")).hasSize(500);
        assertThat(capped.get("filasTruncadas")).isEqualTo(true);
    }

    @Test @WithMockUser(roles="CAJERO") void nonAdminCannotListOrGenerate() {
        graphQl.document("{ listarEntidadesReporte { clave } }").execute().errors().satisfy(errors -> {
            assertThat(errors).hasSize(1); assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
        });
        graphQl.document(GENERATE).variable("i",input("pacientes","nombres")).execute().errors().satisfy(errors -> {
            assertThat(errors).hasSize(1); assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
        });
    }

    UUID patient(String date) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes(id,ci_nit,nombres,apellidos,telefono_whatsapp,created_at) VALUES (?,?, 'Manual', 'Synthetic', '0', ?::timestamptz)",
                id,id.toString().substring(0,30),date);
        return id;
    }
    UUID fixture() {
        UUID patient=patient("2150-02-15T12:00:00Z"), order=UUID.randomUUID(), quota=UUID.randomUUID();
        jdbc.update("INSERT INTO ordenes_cobro(id,paciente_id,monto_subtotal_bs,monto_total_bs,saldo_pendiente_bs,estado) VALUES (?,?,100,100,100,'PENDIENTE')",order,patient);
        jdbc.update("INSERT INTO cuotas_pago(id,orden_cobro_id,numero_cuota,monto_cuota_bs,fecha_vencimiento,estado) VALUES (?,?,1,100,DATE '2150-02-15','PENDIENTE')",quota,order);
        UUID cashier=UUID.fromString("20000000-0000-0000-0000-000000000002"), income=UUID.randomUUID();
        jdbc.update("INSERT INTO movimientos_caja(id,cuota_id,usuario_cajero_id,tipo_movimiento,categoria,metodo_pago,monto_bs,descripcion,fecha_movimiento) VALUES (?,?,?,'INGRESO','COBRO_CUOTA','QR_PAGOFACIL',100,'Synthetic QR',TIMESTAMPTZ '2150-02-15 10:00:00-04')",income,quota,cashier);
        jdbc.update("INSERT INTO recibos(id,movimiento_caja_id,numero_recibo,ci_nit_cliente,razon_social_cliente,concepto,monto_total_bs) VALUES (?,?,?,'0','Synthetic','Synthetic',100)",UUID.randomUUID(),income,"MR-"+UUID.randomUUID());
        jdbc.update("INSERT INTO movimientos_caja(id,usuario_cajero_id,tipo_movimiento,categoria,metodo_pago,monto_bs,descripcion,fecha_movimiento) VALUES (?,?,'EGRESO','OTROS','EFECTIVO',25,'Synthetic expense',TIMESTAMPTZ '2150-02-15 11:00:00-04')",UUID.randomUUID(),cashier);
        UUID service=UUID.randomUUID();
        jdbc.update("INSERT INTO servicios_odontologicos(id,codigo,nombre,precio_base_bs,activo) VALUES (?,?, 'Manual synthetic',25.30,false)",service,"MR-"+service.toString().substring(0,20));
        return patient;
    }
    void restoreCaller() {
        org.springframework.security.core.context.SecurityContextHolder.setContext(
                org.springframework.security.test.context.TestSecurityContextHolder.getContext());
    }
    Object businessSnapshot(UUID patient) {
        restoreCaller();
        var response = graphQl.document("""
                query($id: UUID!, $a: DateTime!, $b: DateTime!) {
                  obtenerPacientePorId(id:$id) { id ciNit nombres apellidos activo }
                  listarOrdenesCobro(pacienteId:$id) { id estado montoTotalBs saldoPendienteBs cuotas { id estado montoCuotaBs fechaPago } }
                  obtenerFlujoCaja(fechaInicio:$a,fechaFin:$b) { totalIngresosBs totalEgresosBs saldoNetoBs movimientos { id montoBs recibo { id numeroRecibo montoTotalBs } } }
                }
                """).variable("id",patient.toString()).variable("a",FROM).variable("b",TO).execute();
        return List.of(response.path("obtenerPacientePorId").entity(Map.class).get(),
                response.path("listarOrdenesCobro").entityList(Map.class).get(),
                response.path("obtenerFlujoCaja").entity(Map.class).get());
    }
}
