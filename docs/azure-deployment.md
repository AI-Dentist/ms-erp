# Deploy MS1 on Azure

The coordinator provisions the host, TLS edge and external `dental-net`. This repository supplies only MS1 and PostgreSQL; it does not deploy Keycloak or n8n. Containers publish no host ports. The public origins are `https://20-83-242-208.sslip.io` and `https://100-60-248-245.sslip.io`.

## Operator setup

1. The coordinator provisions `/home/azureuser/deploy/ms-erp` and configures the GitHub Actions `AZURE_SSH_KEY` secret with a dedicated CI key, not an administrator key. No GitHub deploy key or repository credentials are installed on the host. Verify the host key out of band: `ssh-keyscan` alone does not authenticate a host.
2. Privately copy `deploy/azure/env.template` to `/home/azureuser/deploy/ms-erp/.env`, restrict its permissions and replace placeholders using a secure editor. Never print, commit or share the file. Set unused AI keys empty rather than leaving placeholder values. The root `.env` is gitignored; its `COMPOSE_FILE` selects the Azure compose file.
3. Set `KEYCLOAK_ISSUER_URI` to the eventual GCP issuer. Production defaults to port 8080 (`ERP_PORT` overrides it). Without a reachable issuer MS1 starts but protected operations reject requests. The Spring Boot issuer decoder is already lazy: discovery occurs on a bearer request, not at startup; the ERP audience validator still applies. Production refuses the `dev` or `local` profiles and legacy dev-mode bypass. Set the PagoFácil secret privately even in mock mode.
4. CI checks out the private repository and synchronizes it over SSH on main/master pushes or manual dispatch. The rsync exclusion preserves the server-only `.env`; CI then runs `docker compose up -d --build --remove-orphans` from the deployment root. Inspect container health without exposing credentials.

## External dependencies and limits

PostgreSQL stores data in the named volume mounted at `/var/lib/postgresql` (PostgreSQL 18 layout). The postgres healthcheck gates MS1 startup; the runtime image has no HTTP healthcheck utility. No image build embeds keys. The public edge and routing belong to platform-infrastructure.

This compose file does not provision RabbitMQ, S3 or the reporting database role. Configure `ERP_RABBIT_HOST`, `ERP_RABBIT_PORT`, `ERP_RABBIT_USER` and `ERP_RABBIT_PASSWORD` for the coordinator-provided broker; configure `ERP_RECEIPTS_MODE` and the applicable `ERP_RECEIPTS_MINIO_*` values for private S3 storage; provision the read-only reporting role and set `ERP_REPORT_DB_URL`, `ERP_REPORT_DB_USER`, `ERP_REPORT_DB_PASSWORD`. See [S3 receipts](s3-receipts.md) and the reporting role instructions in `scripts/reporting-role.sql`. Until these integrations exist, do not interpret a running container as full business readiness. The example n8n URL is a contract placeholder, not a deployed n8n service; enable it only after provisioning its endpoint and secret.
