package com.dentalplatform.erp.localprofile;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Plain HTTP helpers: requests cross the real servlet filter chain on a random port. */
final class LocalProfileHttpSupport {

    static final String ORIGIN = "http://localhost:4201";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private LocalProfileHttpSupport() {
    }

    static HttpResponse<String> graphql(int port, String query, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        headers.forEach(request::header);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(
                JSON.writeValueAsString(Map.of("query", query)))).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> preflight(int port, String path, String origin, String method, String headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Origin", origin)
                .header("Access-Control-Request-Method", method);
        if (headers != null) {
            request.header("Access-Control-Request-Headers", headers);
        }
        return HTTP.send(request.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
