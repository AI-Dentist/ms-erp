# Private receipt PDFs

## Select storage

Production deployments should set `ERP_RECEIPTS_MODE=minio`. The checked-in configuration explicitly selects `local` by default for development/tests. Local mode writes `file:` URIs under `ERP_RECEIPTS_STORAGE_DIR` and is not a browser download service.

MinIO mode requires these externally supplied environment variables (no production credentials are included):

| Variable | Value |
| --- | --- |
| `ERP_RECEIPTS_MINIO_ENDPOINT` | S3-compatible HTTP(S) endpoint reachable by both ERP and authorized download clients; use HTTPS outside local development |
| `ERP_RECEIPTS_MINIO_ACCESS_KEY` | Dedicated service account access key |
| `ERP_RECEIPTS_MINIO_SECRET_KEY` | Dedicated service account secret, supplied through deployment secret management |
| `ERP_RECEIPTS_MINIO_BUCKET` | Pre-provisioned private bucket, lowercase letters/digits/hyphens, 3–63 characters |
| `ERP_RECEIPTS_MINIO_REGION` | Explicit bucket region; default `us-east-1` |
| `ERP_RECEIPTS_MINIO_EXPIRY_SECONDS` | Signed GET lifetime; default 300, supported range 1–604800; keep short |

Typed configuration under `erp.receipts.minio` bounds connect/read/write/call timeouts to 1–30000 milliseconds. Defaults are 1000/2000/2000/5000 ms respectively. HTTP connection retries and redirects are disabled. Invalid/missing configuration fails startup when MinIO is selected; startup constructs the SDK client without contacting the endpoint. There is no silent local fallback.

## Operator provisioning (before accepting payments)

1. Create the bucket using your operator credentials and approved MinIO/S3 administration tooling. The application does not create buckets, probe them at startup, or grant access.
2. Keep anonymous/public access disabled. Do not add a public-read ACL or broad bucket policy. Grant the dedicated application identity only `s3:PutObject` and `s3:GetObject` on `arn:aws:s3:::YOUR_PRIVATE_BUCKET/*` (replace the bucket identifier when applying the policy). No bucket-list, bucket-create, policy-write, or delete permission is required by this adapter. Configure region explicitly to avoid SDK bucket-location discovery.
3. Configure TLS, retention, encryption, backup and any versioning policy operationally. Repeated uploads to the same receipt UUID overwrite the same object key; versioned buckets can retain older versions under their own lifecycle policy.
4. Set the variables above and verify real authenticated upload/download and anonymous denial against your deployment. Endpoint DNS, certificates, permissions and public-access configuration are operator responsibilities, not established by the local stub tests.

The endpoint must be the externally usable signed host: rewriting host/path/query through a proxy invalidates SigV4. Credentials belong on the server only, never in frontend configuration, source control or logs.

## Persistence and authorized download

`pdf_url_s3` stores a stable `s3://bucket/<receipt-UUID>.pdf` reference. Object keys contain no patient identifiers. Upload uses the official MinIO Java SDK 8.5.17 with `application/pdf`; one UUID always targets one key. No database migration is needed.

An authorized GraphQL cashflow query can select `movimientos { recibo { id pdfUrlS3 } }`. `Recibo.pdfUrlS3` resolves a fresh bounded SigV4 GET URL for each request; the signed URL is never written back to the database. Existing root finance authorization remains unchanged, and the field independently requires ADMIN or CAJERO, including when called with a constructed receipt source. The current finance model grants these roles global receipt access; there is no invented tenant/patient ownership rule. RECEPCIONISTA can still read allowed cashflow fields but cannot resolve downloads. No new REST route is introduced.

The server accepts no client URL or object key for signing. The stored reference must exactly match the configured bucket and source receipt UUID; another bucket/key, traversal, query suffix or old local reference fails closed. Migrating existing local PDFs to MinIO requires an operator-controlled artifact migration/reference update; selecting MinIO does not make old local references downloadable.

Treat signed URLs as bearer secrets: do not log, share, cache in persistent client storage, or include them in analytics. Anyone possessing a link can use it until expiry. Field authorization controls issuance, not subsequent bearer use.

## Failure boundary and verification

The legal receipt row, paid installment, order balance and cash movement commit before PDF generation/storage. Successful upload then saves the stable reference in the existing separate transaction. Upload/PDF failure leaves payment committed and `pdf_url_s3` null; the mutation's original receipt snapshot also has a null URL. Query cashflow later to obtain the artifact URL.

**Limitation:** PDF side effects remain best-effort after commit, with no durable automatic retry. Do not repeat the financial payment to repair a PDF. A crash between upload and reference persistence can leave an unreferenced object. No new retry/recovery API is provided here.

Focused tests use a real SDK against a loopback HTTP stub (body, MIME type, bucket/key, SigV4 upload, overwrite, denial, timeout, fresh presigning and reference rejection), real embedded PostgreSQL payment/reference persistence, and GraphQL role checks. They do not establish live MinIO/Compose, external TLS or production IAM acceptance.

For isolated concurrent verification, Maven supports `-Derp.build.directory=<absolute-system-temp-directory>` and `-Derp.test.reports.directory=<unique-reports-directory>`. Run only focused receipt tests while other writers are active; do not run `clean` against shared output.
