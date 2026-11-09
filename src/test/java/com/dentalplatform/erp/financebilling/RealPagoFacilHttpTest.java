package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.dentalplatform.erp.financebilling.application.port.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class RealPagoFacilHttpTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> paths = new CopyOnWriteArrayList<>();
    static final Map<String,JsonNode> requests = new ConcurrentHashMap<>();
    static final HttpServer provider = provider();
    static volatile String status = "2", paidAmount = "0.35";
    static volatile boolean badLogin, failReceipt, wrongIdentity;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;
    String admin, order, quota, reference, company;

    static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); paths.add(path);
                JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                String values;
                if (path.equals("/login")) values = badLogin ? "{}" : "{\"accessToken\":\"synthetic\",\"expiresInMinutes\":60}";
                else if (path.equals("/list-enabled-services")) values = "[{\"paymentMethodId\":4,\"currencyName\":\"BOB\"}]";
                else if (path.equals("/generate-qr")) {
                    String company = body.path("paymentNumber").asString();
                    String ref = "PF-" + UUID.randomUUID(); requests.put(ref, body); requests.put(company, body);
                    values = JSON.writeValueAsString(Map.of("transactionId",ref,"qrBase64","data:image/png;base64," + PagoFacilProtocolTest.PNG,
                            "expirationDate",OffsetDateTime.now().plusMinutes(15).toString()));
                    requests.put("last", JSON.readTree("{\"reference\":\""+ref+"\",\"company\":\""+company+"\"}"));
                } else {
                    String ref = body.path("pagofacilTransactionId").asString();
                    JsonNode original = requests.get(ref);
                    values = JSON.writeValueAsString(Map.of("paymentStatus",status,"amount",paidAmount,"currencyId",2,
                            "companyTransactionId", wrongIdentity ? "unrelated-sale" : original.path("paymentNumber").asString()));
                }
                byte[] bytes = ("{\"error\":0,\"status\":1,\"values\":" + values + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        try(var c=EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();var s=c.createStatement()) {
            if(!s.executeQuery("SELECT 1 FROM pg_database WHERE datname='erp_pf_real'").next()) s.execute("CREATE DATABASE erp_pf_real");
        }
        r.add("spring.datasource.url",()->EmbeddedPostgresSupport.jdbcUrl("erp_pf_real"));
        r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup",()->false);
        r.add("erp.catalog-outbox.enabled",()->false); r.add("erp.billing-saga.outbox.enabled",()->false);
        r.add("erp.pagofacil.mock-enabled",()->false);
        r.add("erp.pagofacil.token-service",()->"synthetic-service"); r.add("erp.pagofacil.token-secret",()->"synthetic-secret");
        r.add("erp.pagofacil.base-url",()->"http://127.0.0.1:"+provider.getAddress().getPort());
        r.add("erp.pagofacil.callback-url",()->"https://example.invalid/api/v1/webhooks/pagofacil");
    }
    @AfterAll static void stop() { provider.stop(0); }
    @BeforeEach void setup() throws Exception {
        status="2"; paidAmount="0.35"; badLogin=false; wrongIdentity=false; paths.clear();
        admin="t12-"+UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios(rol_id,username,email,nombre_completo,activo) SELECT id,?,?,'Synthetic T12',true FROM roles WHERE codigo='ADMIN'",admin,admin+"@test.invalid");
        String service = data("mutation { crearServicioOdontologico(input:{codigo:\"PF-"+UUID.randomUUID().toString().substring(0,8)+"\",nombre:\"Synthetic QR\",precioBaseBs:350}) {id} }")
                .at("/crearServicioOdontologico/id").asString();
        JsonNode created = data("mutation { crearOrdenCobroManual(input:{pacienteId:\"60000000-0000-0000-0000-000000000001\",items:[{servicioId:\""+service+"\",cantidad:1}]}) {id cuotas{id}} }").path("crearOrdenCobroManual");
        order=created.path("id").asString(); quota=created.at("/cuotas/0/id").asString();
    }
    HttpResponse<String> post(String path,Object body,String user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if(user!=null) request.header("X-Dev-User",user);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode graph(String query,String user) throws Exception {
        var response=post("/graphql",Map.of("query",query),user);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    JsonNode data(String query) throws Exception {
        var response=graph(query,admin); assertThat(response.has("errors")).as(response.toString()).isFalse(); return response.path("data");
    }
    void generate() throws Exception {
        JsonNode qr=data("mutation {generarQrPagoFacil(cuotaId:\""+quota+"\",ciNitCliente:\"12345\",razonSocialCliente:\"Cliente sintético\") {pagofacilTransactionId qrImagenBase64 montoBs estado fechaExpiracion}}").path("generarQrPagoFacil");
        assertThat(qr.path("montoBs").decimalValue()).isEqualByComparingTo("350");
        assertThat(qr.path("estado").asString()).isEqualTo("GENERADO");
        assertThat(qr.path("qrImagenBase64").asString()).isEqualTo(PagoFacilProtocolTest.PNG);
        assertThat(qr.path("fechaExpiracion").asString()).isNotBlank();
        reference=qr.path("pagofacilTransactionId").asString(); company=requests.get("last").path("company").asString();
        assertThat(reference).startsWith("PF-"); assertThat(company).startsWith("ERP-QR-");
        assertThat(requests.get(reference).path("amount").decimalValue()).isEqualByComparingTo("0.35");
    }
    String verifyQuery(String ref) { return "mutation {verificarPagoQrPagoFacil(pagofacilTransactionId:\""+ref+"\") {id numeroRecibo ciNitCliente razonSocialCliente montoTotalBs}}"; }
    JsonNode verify(String ref) throws Exception { return data(verifyQuery(ref)).path("verificarPagoQrPagoFacil"); }
    JsonNode cash() throws Exception {
        return data("{obtenerFlujoCaja(fechaInicio:\"2020-01-01T00:00:00Z\",fechaFin:\"2099-01-01T00:00:00Z\") {totalIngresosBs movimientos{montoBs descripcion metodoPago recibo{id numeroRecibo montoTotalBs}}}}").path("obtenerFlujoCaja");
    }
    void state(boolean paid) throws Exception {
        JsonNode detail=data("{obtenerOrdenCobroDetalle(id:\""+order+"\"){montoTotalBs saldoPendienteBs cuotas{estado montoCuotaBs}}}").path("obtenerOrdenCobroDetalle");
        assertThat(detail.path("montoTotalBs").decimalValue()).isEqualByComparingTo("350");
        assertThat(detail.path("saldoPendienteBs").decimalValue()).isEqualByComparingTo(paid?"0":"350");
        assertThat(detail.at("/cuotas/0/montoCuotaBs").decimalValue()).isEqualByComparingTo("350");
        assertThat(detail.at("/cuotas/0/estado").asString()).isEqualTo(paid?"PAGADA":"PENDIENTE");
        var movements=new ArrayList<JsonNode>();
        for(JsonNode m:cash().path("movimientos")) if(m.path("descripcion").asString().contains(order)) movements.add(m);
        assertThat(movements).hasSize(paid?1:0);
        if(paid) {
            assertThat(movements.getFirst().path("montoBs").decimalValue()).isEqualByComparingTo("350");
            assertThat(movements.getFirst().path("metodoPago").asString()).isEqualTo("QR_PAGOFACIL");
            assertThat(movements.getFirst().at("/recibo/montoTotalBs").decimalValue()).isEqualByComparingTo("350");
        }
    }
    @Test void generationUsesRealHttpAndKeepsFullErpAmount() throws Exception { generate(); state(false); }
    @Test void activeQueryConfirmsFullAmountAndRetryReturnsSameReceiptWithoutProviderCall() throws Exception {
        generate(); JsonNode receipt=verify(reference);
        assertThat(receipt.path("montoTotalBs").decimalValue()).isEqualByComparingTo("350");
        assertThat(receipt.path("ciNitCliente").asString()).isEqualTo("12345");
        assertThat(receipt.path("razonSocialCliente").asString()).isEqualTo("Cliente sintético");
        int calls=paths.size(); assertThat(verify(reference)).isEqualTo(receipt); assertThat(paths).hasSize(calls); state(true);
    }
    @Test void officialCallbackLooksUpCompanyNumberRequeriesAndReturnsExactEnvelope() throws Exception {
        generate();
        var response=post("/api/v1/webhooks/pagofacil",Map.of("PedidoID",company,"Fecha","2026-11-09","Hora","10:00:00","MetodoPago","QR","Estado",2),null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree("{\"error\":0,\"status\":1,\"message\":\"Pago confirmado correctamente\",\"values\":true}"));
        assertThat(paths).contains("/query-transaction"); state(true);
        JsonNode receipt=verify(reference); assertThat(verify(company)).isEqualTo(receipt);
    }
    @Test void unpaidMismatchAndForeignTransactionLeaveAllPublicFinancialStateUnchanged() throws Exception {
        generate(); JsonNode before=cash();
        for(String[] rejected:List.of(new String[]{"1","0.35"},new String[]{"2","0.34"})) {
            status=rejected[0]; paidAmount=rejected[1];
            assertThat(graph(verifyQuery(reference),admin).has("errors")).isTrue(); state(false); assertThat(cash()).isEqualTo(before);
            var callback=post("/api/v1/webhooks/pagofacil",Map.of("PedidoID",company,"Estado",2),null);
            assertThat(callback.statusCode()).isEqualTo(400); state(false); assertThat(cash()).isEqualTo(before);
        }
        status="2"; paidAmount="0.35"; wrongIdentity=true;
        assertThat(graph(verifyQuery(reference),admin).has("errors")).isTrue(); state(false); assertThat(cash()).isEqualTo(before);
    }
    @Test void originalAmountAndAllSpecifiedPaidStatusesAreAccepted() throws Exception {
        generate(); status="COMPLETED"; paidAmount="350.00"; verify(reference); state(true);
    }
    @Test void officialCallbackNeverAuthenticatesThroughBodyStatusAloneOrMockFormat() throws Exception {
        generate(); status="1";
        assertThat(post("/api/v1/webhooks/pagofacil",Map.of("PedidoID",company,"Estado","PAGADO"),null).statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/webhooks/pagofacil",Map.of("transactionId",reference,"amountBs",350,"confirmedAt",OffsetDateTime.now().toString()),null).statusCode()).isEqualTo(401);
        state(false);
    }
    @Test void cashierAllowedButDentistAndAnonymousCannotVerify() throws Exception {
        generate();
        String dentist="dentist-"+UUID.randomUUID(), cashier="cashier-"+UUID.randomUUID();
        for(var user:Map.of(dentist,"ODONTOLOGO",cashier,"CAJERO").entrySet())
            jdbc.update("INSERT INTO usuarios(rol_id,username,email,nombre_completo,activo) SELECT id,?,?,'Synthetic',true FROM roles WHERE codigo=?",user.getKey(),user.getKey()+"@test.invalid",user.getValue());
        int calls=paths.size();
        assertThat(graph(verifyQuery(reference),dentist).path("errors").get(0).at("/extensions/classification").asString()).isEqualTo("FORBIDDEN");
        assertThat(post("/graphql",Map.of("query",verifyQuery(reference)),null).statusCode()).isEqualTo(401);
        assertThat(paths).hasSize(calls); state(false);
        assertThat(graph(verifyQuery(reference),cashier).has("errors")).isFalse(); state(true);
    }
    @Test void concurrentQueryAndCallbackProduceOneFullReceipt() throws Exception {
        generate();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var query=executor.submit(()->verify(reference));
            var callback=executor.submit(()->post("/api/v1/webhooks/pagofacil",Map.of("PedidoID",company),null));
            JsonNode receipt=query.get(30,TimeUnit.SECONDS);
            assertThat(callback.get(30,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(verify(reference)).isEqualTo(receipt); state(true);
        }
    }
}
