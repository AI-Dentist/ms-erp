package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort.SolicitudQr;
import com.dentalplatform.erp.financebilling.infrastructure.external.PagoFacilHttpAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures the header names exactly as written on the wire. com.sun HttpServer normalizes header-name case,
 * so this raw HTTP/1.1 loopback stub is the only way to pin the provider's lowercase credential headers.
 * Synthetic credentials only; never contacts PagoFacil.
 */
class PagoFacilRawHeaderNamesTest {
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";
    static final Map<String, String> VALUES = Map.of(
            "/login", "{\"accessToken\":\"synthetic-bearer\",\"expiresInMinutes\":10}",
            "/generate-qr", "{\"transactionId\":\"provider-123\",\"qrBase64\":\"" + PNG + "\"}",
            "/query-transaction", "{\"paymentStatus\":2,\"amount\":\"0.35\",\"currencyId\":2}");
    final Map<String, List<String>> headerNames = new ConcurrentHashMap<>();
    ServerSocket server;
    Thread acceptor;

    @BeforeEach void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) { serve(socket); }
                catch (IOException ignored) { /* closed by stop() */ }
            }
        });
    }

    @AfterEach void stop() throws IOException { server.close(); }

    private void serve(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String requestLine = line(in);
        List<String> names = new ArrayList<>();
        int length = 0;
        for (String header = line(in); !header.isEmpty(); header = line(in)) {
            String name = header.substring(0, header.indexOf(':'));
            names.add(name);
            if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
        }
        in.readNBytes(length);
        String path = requestLine.split(" ")[1];
        headerNames.put(path, names);
        byte[] body = ("{\"error\":0,\"status\":1,\"values\":" + VALUES.getOrDefault(path, "null") + "}")
                .getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static String line(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) if (b != '\r') buffer.write(b);
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    @Test void credentialHeadersUseTheProviderLowercaseNamesOnTheirOwnEndpoints() {
        var adapter = new PagoFacilHttpAdapter(false, "http://127.0.0.1:" + server.getLocalPort(),
                "synthetic-service", "synthetic-secret", 4, "https://example.invalid/callback", Clock.systemUTC());
        adapter.generar(new SolicitudQr("ERP-QR-synthetic", new BigDecimal("350.00"), "12345", "Cliente sintético",
                "patient-code", "Cuota odontológica"));
        adapter.consultarTransaccion("provider-123");

        assertThat(headerNames.get("/login")).contains("tctokenservice", "tctokensecret")
                .doesNotContain("tcTokenService", "tcTokenSecret", "Authorization");
        assertThat(headerNames.get("/query-transaction")).contains("tctokensecret", "Authorization")
                .doesNotContain("tcTokenSecret", "tctokenservice", "tcTokenService");
        assertThat(headerNames.get("/generate-qr")).contains("Authorization")
                .noneMatch(name -> name.equalsIgnoreCase("tctokenservice") || name.equalsIgnoreCase("tctokensecret"));
    }
}
