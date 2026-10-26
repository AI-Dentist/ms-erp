package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MinioReceiptStorageTest {
    @TempDir Path directory;
    HttpServer server;
    ExecutorService executor;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final List<String> types = new CopyOnWriteArrayList<>();
    final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile int delay;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            types.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(exchange.getRequestBody().readAllBytes());
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().add("ETag", "\"receipt-etag\"");
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                byte[] response = status == 200 ? new byte[0] : ("<Error><Code>AccessDenied</Code>"
                        + "<Message>Denied</Message><RequestId>test</RequestId></Error>").getBytes();
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    ReceiptStorageConfig.Minio config(int expiry, int timeout) {
        return new ReceiptStorageConfig.Minio("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-access", "test-secret-not-production", "private-receipts", "us-east-1",
                expiry, timeout, timeout, timeout, timeout);
    }
    ReceiptStoragePort storage(ReceiptStorageConfig.Minio config) {
        return new ReceiptStorageConfig().receiptStorage(new ReceiptStorageConfig.Settings("minio", directory.toString(), config));
    }
    @Test void realSdkStoresExactPdfAndOverwritesSamePrivateKeyWithoutBucketCalls() {
        var storage = storage(config(300, 1000));
        assertThat(paths).isEmpty();
        UUID id = UUID.randomUUID();
        byte[] bytes = "%PDF-1.7\nprivate receipt".getBytes();
        String ref = storage.guardar(id, bytes);
        assertThat(ref).isEqualTo("s3://private-receipts/" + id + ".pdf");
        assertThat(storage.guardar(id, bytes)).isEqualTo(ref);
        assertThat(paths).containsExactly("PUT /private-receipts/" + id + ".pdf", "PUT /private-receipts/" + id + ".pdf");
        assertThat(types).containsExactly("application/pdf", "application/pdf");
        assertThat(bodies).allSatisfy(body -> assertThat(body).isEqualTo(bytes));
        assertThat(auth).allSatisfy(value -> assertThat(value).startsWith("AWS4-HMAC-SHA256 Credential=test-access/")
                .contains("/us-east-1/s3/aws4_request", "Signature="));
        assertThat(directory).isEmptyDirectory();
    }
    @Test void freshBoundedPresignedGetUsesStableReferenceWithoutNetwork() throws Exception {
        var storage = storage(config(300, 1000));
        UUID id = UUID.randomUUID();
        String ref = "s3://private-receipts/" + id + ".pdf";
        String first = storage.resolverDescarga(id, ref);
        Thread.sleep(1100); // SigV4 time resolution is one second.
        String second = storage.resolverDescarga(id, ref);
        assertThat(second).isNotEqualTo(first);
        assertThat(URI.create(second).getPath()).isEqualTo("/private-receipts/" + id + ".pdf");
        assertThat(URI.create(second).getQuery()).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Expires=300", "X-Amz-Signature=");
        assertThat(paths).isEmpty();
        assertThat(storage.resolverDescarga(id, null)).isNull();
        for (String invalid : List.of("https://external/file", ref + "?x=1", ref.replace("private-receipts", "other-bucket"),
                "s3://private-receipts/../" + id + ".pdf", "s3://private-receipts/" + UUID.randomUUID() + ".pdf")) {
            assertThatThrownBy(() -> storage.resolverDescarga(id, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void deniedUploadFailsClosedWithoutLocalFallbackOrSensitiveError() {
        status = 403;
        var storage = storage(config(300, 1000));
        assertThatThrownBy(() -> storage.guardar(UUID.randomUUID(), new byte[]{37, 80, 68, 70}))
                .isInstanceOf(IllegalStateException.class).hasMessage("No se pudo almacenar el PDF del recibo").hasNoCause();
        assertThat(paths).hasSize(1);
        assertThat(directory).isEmptyDirectory();
    }
    @Test void slowServerIsBoundedAndDoesNotFallback() {
        delay = 1500;
        var storage = storage(config(300, 200));
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> storage.guardar(UUID.randomUUID(), new byte[]{1})).isInstanceOf(IllegalStateException.class));
        assertThat(directory).isEmptyDirectory();
    }
    @Test void configurationRejectsMissingCredentialsUnknownModeAndUnboundedValues() {
        var factory = new ReceiptStorageConfig();
        assertThatThrownBy(() -> factory.receiptStorage(new ReceiptStorageConfig.Settings("other", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.receiptStorage(new ReceiptStorageConfig.Settings("minio", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        for (int timeout : new int[]{0, -1, 30001}) assertThatThrownBy(() -> storage(config(300, timeout)))
                .isInstanceOf(IllegalArgumentException.class);
        for (int expiry : new int[]{0, 604801}) assertThatThrownBy(() -> storage(config(expiry, 1000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage(config(1, 1))).isNotNull();
        assertThat(storage(config(604800, 30000))).isNotNull();
        assertThatThrownBy(() -> storage(new ReceiptStorageConfig.Minio("https://localhost", "", "", "private-receipts",
                null, null, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage(new ReceiptStorageConfig.Minio("https://user:password@localhost", "test", "test", "private-receipts",
                null, null, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(config(300, 1000).toString()).doesNotContain("test-access", "test-secret", "127.0.0.1");
        assertThat(paths).isEmpty();
    }
    @Test void explicitLocalModePreservesFileUriAndRejectsOtherReceipts() throws Exception {
        var storage = new ReceiptStorageConfig().receiptStorage(new ReceiptStorageConfig.Settings("local", directory.toString(), null));
        UUID id = UUID.randomUUID();
        byte[] pdf = {37, 80, 68, 70};
        String reference = storage.guardar(id, pdf);
        assertThat(Files.readAllBytes(Path.of(URI.create(reference)))).isEqualTo(pdf);
        assertThat(storage.resolverDescarga(id, reference)).isEqualTo(reference);
        assertThatThrownBy(() -> storage.resolverDescarga(UUID.randomUUID(), reference)).isInstanceOf(IllegalArgumentException.class);
    }
}
