package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import io.minio.MinioClient;
import io.minio.credentials.*;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptStorageConfig.Settings.class)
public class ReceiptStorageConfig {
    @ConfigurationProperties("erp.receipts")
    public record Settings(String mode, String storageDir, Minio minio) { }

    /** Keeps the {@code erp.receipts.minio.*} prefix for compatibility; it configures any S3 endpoint. */
    public record Minio(String endpoint, String accessKey, String secretKey, String bucket, String region,
                        Integer expirySeconds, Integer connectTimeoutMs, Integer readTimeoutMs,
                        Integer writeTimeoutMs, Integer callTimeoutMs, String provider) {
        static final String AWS = "aws";
        static final String S3_COMPATIBLE = "s3-compatible";

        @ConstructorBinding
        public Minio {
            provider = provider == null || provider.isBlank() ? S3_COMPATIBLE : provider;
            region = region == null || region.isBlank() ? (AWS.equals(provider) ? null : "us-east-1") : region;
            expirySeconds = expirySeconds == null ? 300 : expirySeconds;
            connectTimeoutMs = connectTimeoutMs == null ? 1000 : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs == null ? 2000 : readTimeoutMs;
            writeTimeoutMs = writeTimeoutMs == null ? 2000 : writeTimeoutMs;
            callTimeoutMs = callTimeoutMs == null ? 5000 : callTimeoutMs;
        }
        /** Pre-AWS shape: s3-compatible provider (SeaweedFS, other emulators). */
        public Minio(String endpoint, String accessKey, String secretKey, String bucket, String region,
                     Integer expirySeconds, Integer connectTimeoutMs, Integer readTimeoutMs,
                     Integer writeTimeoutMs, Integer callTimeoutMs) {
            this(endpoint, accessKey, secretKey, bucket, region, expirySeconds, connectTimeoutMs, readTimeoutMs,
                    writeTimeoutMs, callTimeoutMs, null);
        }
        @Override public String toString() { return "Minio[configuration redacted]"; }

        boolean aws() { return AWS.equals(provider); }
        boolean hasStaticKeys() { return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank(); }

        /** AWS defaults to the regional endpoint; other providers must configure one explicitly. */
        public String effectiveEndpoint() {
            return aws() && (endpoint == null || endpoint.isBlank()) ? "https://s3." + region + ".amazonaws.com" : endpoint;
        }

        public void validate() {
            try {
                if (!aws() && !S3_COMPATIBLE.equals(provider)) throw new IllegalArgumentException();
                URI uri = URI.create(effectiveEndpoint());
                if (!("https".equals(uri.getScheme()) || (!aws() && "http".equals(uri.getScheme()))) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
                boolean anyKey = (accessKey != null && !accessKey.isBlank()) || (secretKey != null && !secretKey.isBlank());
                // AWS may use the credential chain (no keys); every other provider needs both static keys.
                if (aws() ? anyKey && !hasStaticKeys() : !hasStaticKeys()) throw new IllegalArgumentException();
                // Signing and presigning need an explicit region; never rely on bucket-location discovery.
                if (region == null || !region.matches("[a-z0-9]+(-[a-z0-9]+)+")
                        || bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw new IllegalArgumentException();
                if (expirySeconds < 1 || expirySeconds > 604800) throw new IllegalArgumentException();
                for (int timeout : new int[]{connectTimeoutMs, readTimeoutMs, writeTimeoutMs, callTimeoutMs}) {
                    if (timeout < 1 || timeout > 30000) throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                // Never include credentials or the configured endpoint in errors.
                throw new IllegalArgumentException("Configuración S3 de recibos inválida");
            }
        }
    }

    /** Env vars, shared config/profile, then ECS task role / EC2 instance profile / EKS web identity. */
    static List<Provider> awsChain(OkHttpClient http) {
        return List.of(new AwsEnvironmentProvider(), new AwsConfigProvider(null, null), new IamAwsProvider(null, http));
    }

    static Provider credentialsProvider(Minio config, OkHttpClient http) {
        if (config.hasStaticKeys()) return new StaticProvider(config.accessKey(), config.secretKey(), null);
        return new ChainedProvider(awsChain(http).toArray(Provider[]::new));
    }

    static ReceiptStoragePort create(Minio config, Provider credentials) {
        return create(config, credentials, new OkHttpClient());
    }

    private static ReceiptStoragePort create(Minio config, Provider credentials, OkHttpClient http) {
        config.validate();
        var client = MinioClient.builder().endpoint(config.effectiveEndpoint()).credentialsProvider(credentials)
                .region(config.region()).httpClient(http).build();
        return new MinioReceiptStorageAdapter(client, config.bucket(), config.expirySeconds());
    }

    @Bean
    ReceiptStoragePort receiptStorage(Settings settings) {
        if ("local".equals(settings.mode())) {
            if (settings.storageDir() == null || settings.storageDir().isBlank())
                throw new IllegalArgumentException("Directorio local de recibos requerido");
            return new LocalReceiptStorageAdapter(settings.storageDir());
        }
        if (!("minio".equals(settings.mode()) || "s3".equals(settings.mode())) || settings.minio() == null)
            throw new IllegalArgumentException("Seleccione almacenamiento de recibos local o s3 y configure sus parámetros");
        var config = settings.minio();
        config.validate();
        var http = new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.writeTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(config.callTimeoutMs(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        return create(config, credentialsProvider(config, http), http);
    }
}
