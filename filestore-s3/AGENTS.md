# DOX — filestore-s3

Module-level docs for the S3 backend (AWS S3 / MinIO / Google Cloud Storage). Java sources are documented in the package-level `AGENTS.md` under `src/main/java/...` and `src/test/java/...`.

| File | Purpose |
|---|---|
| `CONFIGURATION.md` | Every configuration surface: component properties, Path A (judo-platform env vars) vs Path B (direct `.cfg`), servlet config, test `.env`; six silent-failure traps (env typo, missing config, servlet feature has no S3 conditional, `protocol` collisions, `endpoint` scheme, bucket must pre-exist); worked GCS-in-judo-platform example. |
| `GCS_ASSESSMENT.md` | Decision doc — should GCS be used: verdict, benefits, the two client-library multipart defects and their fix in `putLargeFile`, costs (significant/moderate/minor), what would make it production-ready. |
| `GCS_INTEROP.md` | How-to for GCS: `gcloud` and Cloud Console provisioning (bucket, service account, HMAC key), `.env`/Karaf config, running `GcsS3FileStoreServiceTest`, known quirks §6.1–6.7 (multipart fix, missing `size` meta, no abort, no bucket create, AWS CLI checksums, access URL, presigned URLs). |
| `SECURITY.md` | Security review of the full request path (browser → servlets → `FileStoreService` → bucket): trust boundaries, findings S-1…S-15 with severity (static creds, token enforcement off by default, non-expiring tokens, wildcard CORS with credentials, size-limit units, …), bucket-side config, hardening checklist, verified vs unverified. |
