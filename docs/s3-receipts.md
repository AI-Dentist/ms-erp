# Private receipt PDFs (S3)

Receipts are stored in a **private S3 bucket**: local = SeaweedFS (emulator), AWS = Amazon S3. The adapter uses the S3 API through the MinIO Java SDK (client library only; the MinIO *server* is no longer distributed as public container images).

## Local (SeaweedFS)

```bash
docker compose up -d seaweedfs seaweedfs-init   # init creates bucket erp-receipts once, idempotently
ERP_RECEIPTS_MODE=s3 \
ERP_RECEIPTS_MINIO_ENDPOINT=http://localhost:8333 \
ERP_RECEIPTS_MINIO_ACCESS_KEY=erp_dev_access \
ERP_RECEIPTS_MINIO_SECRET_KEY=erp_dev_secret_local_only \
ERP_RECEIPTS_MINIO_BUCKET=erp-receipts \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The credentials in `docker/seaweedfs/s3.json` are **dev-only**, and the port is bound to loopback. Because identities are configured, anonymous requests get `403 AccessDenied` (checked by `LiveIntegrationsIT`). Path-style addressing is used automatically for non-AWS hosts.

## AWS (S3 + IAM role)

| Variable | Value |
| --- | --- |
| `ERP_RECEIPTS_MODE` | `s3` (`minio` is accepted as an alias) |
| `ERP_RECEIPTS_MINIO_PROVIDER` | `aws` |
| `ERP_RECEIPTS_MINIO_REGION` | **Required**, e.g. `us-east-1` (used for signing and presigning) |
| `ERP_RECEIPTS_MINIO_BUCKET` | Pre-created private bucket |
| `ERP_RECEIPTS_MINIO_ENDPOINT` | Optional; defaults to `https://s3.<region>.amazonaws.com` (HTTPS only) |
| `ERP_RECEIPTS_MINIO_ACCESS_KEY` / `_SECRET_KEY` | **Leave unset** to use the AWS credential chain |

With no static keys the credential chain is, in order: `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` env vars, shared config/profile (`~/.aws`), then ECS task role / EC2 instance profile / EKS web identity (`IamAwsProvider`). Prefer a role; static keys still win if both are set. Credentials are fetched lazily, so startup makes no network call.

Startup fails (no silent local fallback) when: the region is missing or malformed, only one of the two keys is set, the endpoint is not HTTPS, or the bucket name is invalid.

Minimal IAM policy for the task/instance role:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject"],
    "Resource": "arn:aws:s3:::YOUR_RECEIPTS_BUCKET/*"
  }]
}
```

Bucket: Block Public Access **on** (all four settings), default encryption SSE-S3 or SSE-KMS (add `kms:GenerateDataKey`/`kms:Decrypt` on the key for SSE-KMS), no public ACL or bucket policy. The adapter never creates buckets or changes policy.

**Limits:** AWS mode is covered by deterministic unit tests (provider-chain composition, region/endpoint, presigned URL region and role credentials with a stubbed provider). It has **not** been exercised against real AWS; verify upload, presigned GET and anonymous denial in your account before accepting payments.

## Other settings

| Variable | Default |
| --- | --- |
| `ERP_RECEIPTS_MINIO_PROVIDER` | `s3-compatible` (static keys required, region defaults to `us-east-1`) |
| `ERP_RECEIPTS_MINIO_EXPIRY_SECONDS` | `300` (range 1–604800; keep short) |
| `ERP_RECEIPTS_MODE` | `local` (writes `file:` URIs under `ERP_RECEIPTS_STORAGE_DIR`; development/tests only) |

Property names keep the historical `erp.receipts.minio.*` prefix for backward compatibility; `provider` is the only new key. Connect/read/write/call timeouts (`...connect-timeout-ms` etc.) are bounded to 1–30000 ms (defaults 1000/2000/2000/5000); retries and redirects are disabled.

## Persistence and download

`pdf_url_s3` stores a stable `s3://bucket/<receipt-UUID>.pdf` reference (no patient data in keys; one UUID = one key, re-uploads overwrite). `Recibo.pdfUrlS3` in GraphQL resolves a fresh SigV4 GET URL per request for ADMIN/CAJERO only; it is never stored. The server never signs client-supplied URLs or keys; a reference for another bucket/key fails closed. Signed URLs are bearer secrets: do not log or cache them. The endpoint must be the externally usable host, since a proxy that rewrites host/path/query invalidates SigV4.

## Failure boundary

Payment, installment, balance and cash movement commit before PDF generation. A PDF or storage failure leaves the payment committed and `pdf_url_s3` null, with no durable automatic retry: do not repeat the payment to repair a PDF.

Every uploaded object has a durable owner, even when it is never linked to its receipt. After the PDF is generated, three steps run in order, each in its own transaction; the upload is in none of them:

1. **Reserve:** commit an ADMIN-visible audit entry `RECIBO_PDF_RESERVADO` (`entidadAfectada = recibos`, `entidadId` = receipt UUID, `detalleJson.referencia` = the exact `s3://bucket/<receipt-UUID>.pdf` key). Adapters compute this key without storage I/O (`ReceiptStoragePort.referencia`); an adapter that cannot name it uploads nothing.
2. **Upload** the object. The returned reference must equal the reserved one, otherwise nothing is linked.
3. **Link:** store the reference in `pdf_url_s3`, which enables the signed download.

If the link fails, its transaction does not commit, or the process stops after the upload, the object is initially referenced by the step 1 audit entry and `pdfUrlS3` stays null. A later ADMIN/CAJERO **query** requesting `Recibo.pdfUrlS3` retries the link on demand: it validates that the reservation exactly matches the adapter's UUID-derived key, checks existence with S3 HEAD (or a local file check), then links that same reference in a separate transaction. It performs **no PUT**, creates no second object and does not repeat the payment. Concurrent/repeated repairs serialize on the receipt and preserve the existing link; a successful repair writes one `RECIBO_PDF_REPARADO` audit entry atomically with the link. Initial mutation responses retain the original receipt snapshot, without initiating a repair.

If the object is absent, storage is unavailable or linking still fails, the optional download stays null; the reservation remains available for another request. No background repair runs and no object is deleted. HEAD uses the existing `s3:GetObject` permission; IAM is unchanged and still needs no `s3:DeleteObject`. A reservation alone does not prove the object exists. Operators find pending artifacts as `RECIBO_PDF_RESERVADO` entries whose receipt has `pdfUrlS3 = null`; retain these ownership entries for the lifetime of their objects. `listarAuditLogs` shows only the latest 500 entries, so use the audit table for complete reconciliation.

## Production note

Self-hosting an S3 server instead of AWS needs a maintained option (for example SeaweedFS with an operator-managed identity file, TLS and backups). MinIO server images are no longer published publicly, so Compose and tests do not use them.
