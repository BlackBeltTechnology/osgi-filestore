# DOX — docs/gcs

Google Cloud Storage as the `filestore-s3` backend. Provider-neutral S3 docs stay in `filestore-s3/` (`CONFIGURATION.md`, `SECURITY.md`); each fact lives in one file and the others link to it.

| File | Purpose |
|---|---|
| `README.md` | Entry point: verdict table, decisions D1–D5 (static HMAC keys, why GCS, migration, downloads via Karaf, GDPR), known gaps, reading order. |
| `assessment.md` | Go/no-go: benefits (metadata contract, footprint, residency), costs and limitations with severity, what makes it production-ready, status of the historical `s3-filestore-comparison-report.md` findings. |
| `setup.md` | How-to: `gcloud` and Cloud Console provisioning (bucket, service account, HMAC key), judo-platform env vars / Karaf `.cfg` / test `.env`, running `GcsS3FileStoreServiceTest`, production bucket lifecycle and smoke check, GCS quirks §7.1–7.5 (no bucket create, AWS CLI checksums, access URL, presigned URLs). |
| `multipart-fix.md` | The two client-library multipart defects (411 missing `Content-Length`, 400 malformed `xmlns`), rejected workarounds, the `putLargeFile` fix, failure handling (200 + `<Error>`, abort on failure), guarding tests, upstream reports. |
