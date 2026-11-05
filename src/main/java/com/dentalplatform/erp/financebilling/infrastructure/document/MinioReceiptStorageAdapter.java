package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import io.minio.*;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.UUID;

/** Private bucket only: never creates buckets, changes policy, or falls back to local storage. */
public final class MinioReceiptStorageAdapter implements ReceiptStoragePort {
    private final MinioClient client;
    private final String bucket;
    private final int expirySeconds;

    public MinioReceiptStorageAdapter(MinioClient client, String bucket, int expirySeconds) {
        this.client = Objects.requireNonNull(client);
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                || expirySeconds < 1 || expirySeconds > 604800) throw new IllegalArgumentException("Configuración S3 inválida");
        this.bucket = bucket;
        this.expirySeconds = expirySeconds;
    }
    private String key(UUID id) { return Objects.requireNonNull(id) + ".pdf"; }
    private String reference(UUID id) { return "s3://" + bucket + "/" + key(id); }

    @Override public String referencia(UUID reciboId) { return reference(reciboId); }

    @Override public boolean existe(UUID reciboId) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key(reciboId)).build());
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            if (e.response() != null && e.response().code() == 404) return false;
            throw new IllegalStateException("No se pudo verificar el PDF del recibo");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo verificar el PDF del recibo");
        }
    }

    @Override public String guardar(UUID reciboId, byte[] pdf) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key(reciboId))
                    .stream(new ByteArrayInputStream(pdf), pdf.length, -1).contentType("application/pdf").build());
            return reference(reciboId);
        } catch (Exception e) {
            // Do not propagate SDK errors containing headers, credentials, endpoints or signed URLs to logging callers.
            throw new IllegalStateException("No se pudo almacenar el PDF del recibo");
        }
    }
    @Override public String resolverDescarga(UUID reciboId, String storedReference) {
        if (storedReference == null) return null;
        if (!reference(reciboId).equals(storedReference)) throw new IllegalArgumentException("Referencia de recibo inválida");
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                    .bucket(bucket).object(key(reciboId)).expiry(expirySeconds).build());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo autorizar la descarga del recibo");
        }
    }
}
