package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptStorageConfig.Settings.class)
public class ReceiptStorageConfig {
    @ConfigurationProperties("erp.receipts")
    public record Settings(String mode, String storageDir, Minio minio) { }

    public record Minio(String endpoint, String accessKey, String secretKey, String bucket, String region,
                        Integer expirySeconds, Integer connectTimeoutMs, Integer readTimeoutMs,
                        Integer writeTimeoutMs, Integer callTimeoutMs) {
        public Minio {
            region = region == null ? "us-east-1" : region;
            expirySeconds = expirySeconds == null ? 300 : expirySeconds;
            connectTimeoutMs = connectTimeoutMs == null ? 1000 : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs == null ? 2000 : readTimeoutMs;
            writeTimeoutMs = writeTimeoutMs == null ? 2000 : writeTimeoutMs;
            callTimeoutMs = callTimeoutMs == null ? 5000 : callTimeoutMs;
        }
        @Override public String toString() { return "Minio[configuration redacted]"; }

        public void validate() {
            try {
                URI uri = URI.create(endpoint);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
                if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()
                        || bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                        || region.isBlank()) throw new IllegalArgumentException();
                if (expirySeconds < 1 || expirySeconds > 604800) throw new IllegalArgumentException();
                for (int timeout : new int[]{connectTimeoutMs, readTimeoutMs, writeTimeoutMs, callTimeoutMs}) {
                    if (timeout < 1 || timeout > 30000) throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                // Never include credentials or the configured endpoint in errors.
                throw new IllegalArgumentException("Configuración MinIO de recibos inválida");
            }
        }
    }

    @Bean
    ReceiptStoragePort receiptStorage(Settings settings) {
        if ("local".equals(settings.mode())) {
            if (settings.storageDir() == null || settings.storageDir().isBlank())
                throw new IllegalArgumentException("Directorio local de recibos requerido");
            return new LocalReceiptStorageAdapter(settings.storageDir());
        }
        if (!"minio".equals(settings.mode()) || settings.minio() == null)
            throw new IllegalArgumentException("Seleccione almacenamiento de recibos local o minio y configure sus parámetros");
        var config = settings.minio();
        config.validate();
        var http = new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.writeTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(config.callTimeoutMs(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        var client = MinioClient.builder().endpoint(config.endpoint()).credentials(config.accessKey(), config.secretKey())
                .region(config.region()).httpClient(http).build();
        return new MinioReceiptStorageAdapter(client, config.bucket(), config.expirySeconds());
    }
}
