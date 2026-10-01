# Proposal: add-gcs-interop-integration-test

## Why

`filestore-s3` is only ever tested against MinIO (`S3FileStoreServiceTest` + `MinioFixture`). That suite has two blind spots:

1. **The client-construction branch of `activate()` is never executed.** `S3FileStoreServiceTest.setup()` assigns `target.s3Client` directly before calling `activate(null, config)`, so the `accessKey` / `secretKey` / `region` / `endpoint` → `Client.s3()...baseUrlFactory(...)` path (`S3FileStoreService.java:78-94`) has zero coverage. A regression there would ship undetected.
2. **No non-MinIO S3-compatible provider is exercised.** Customers deploying on Google Cloud Storage need evidence the backend works there, and the specific risk is not obvious: `aws-lightweight-client-java` builds its metadata map by filtering response headers on the literal prefix `x-amz-meta-` (`Response.metadata()`), while GCS natively emits `x-goog-*` headers. If GCS returned `x-goog-meta-*`, then `getFileName()`, `getMimeType()` and `getCreateTime()` would silently return `null` and throw NPE / NumberFormatException.

Probing a real GCS bucket answered both questions, and turned up a third, unwelcome one:

- GCS **does** return `x-amz-meta-*` when the request is signed with `AWS4-HMAC-SHA256`, so the metadata contract holds and the small-file path needs no change. That finding is currently undocumented and unguarded.
- **Files ≥ 5 MB could not be stored on GCS at all.** The multipart path failed, and the cause was two defects in the client library, not in `filestore-s3` or in GCS. This change fixes them.

This change makes all three facts *tested* rather than *believed*, and fixes the third.

## What Changes

- **New opt-in integration test** `GcsS3FileStoreServiceTest` running the `FileStoreService` contract against a real Google Cloud Storage bucket over the S3 interoperability (XML) API.
- **Credentials supplied from an `.env` file**, not hard-coded and not committed. A `DotEnv` test helper loads `filestore-s3/.env` (path overridable via `FILESTORE_ENV_FILE`), with real environment variables taking precedence so CI can inject secrets.
- **The test self-gates**: when `GCS_TEST_ENABLED` is not `true` (or the `.env` is absent), every test is skipped. `mvn test` for everyone else is unaffected.
- **`activate()` is exercised for real** — the test does *not* pre-inject `s3Client`; it lets `activate()` build the client from config, closing blind spot #1.
- **A regression guard on the metadata prefix** asserts `getFileName`/`getMimeType`/`getSize`/`getCreateTime` all resolve, which fails loudly if GCS ever switches to `x-goog-meta-*`.
- **The ≥ 5 MB defect is fixed** in `S3FileStoreService.putLargeFile`, and guarded in two complementary ways:
  - `S3FileStoreServiceMultipartWireTest` — a network-free test that replaces the library's public `HttpClient` seam with a recorder and pins the exact wire output: the initiate request carries an *empty* (not absent) body so `Content-Length: 0` is emitted, and the complete document carries no malformed namespace. Run against the original code these two `fixed*` tests fail by name and the other seven pass.
  - `GcsS3FileStoreServiceTest.putAndGetLargeFileViaMultipart` and `putAndGetThreePartFileViaMultipart` — 6 MB and 3-part round trips against a real bucket, byte-exact, with metadata surviving assembly.
- **Setup documentation** `filestore-s3/GCS_INTEROP.md` covering the `gcloud` CLI path and the Cloud Console click-path for creating the bucket, service account, IAM binding and HMAC key, plus the full defect analysis, the evidence, and the rejected/available fix options.
- **`.gitignore`** gains `**/.env` so real credentials cannot be committed.

## Capabilities

- **gcs-interop**: verified S3-interoperability of `S3FileStoreService` against Google Cloud Storage — the metadata-prefix contract, the small-file path, and the documented, tested absence of large-file support.

## Impact

- **One production change**: `S3FileStoreService.putLargeFile` no longer uses the library's `Multipart` helper; it drives initiate / upload-part / complete itself through the public low-level `Request` API, with the complete document built by the library's own public `Xml` builder. 47 insertions / 11 deletions, one method plus a `readFully` helper. Everything else — `putSmallFile`, the whole metadata contract — is untouched.
- **Verified on both backends**: the 6 MB and 3-part GCS tests pass, and the existing MinIO `testPutAndGetLargeFileMultipart` still passes, i.e. no regression for Amazon-compatible backends.
- **Memory and threads unchanged or better**: parts stream through one reused 5 MB buffer (measured: 100 × 6 MB under `-Xmx40m` peaked at 29 MB heap), and no threads are created — the library's helper spawned an unbounded cached thread pool per upload.
- **No new Maven dependencies.** `DotEnv` is ~30 lines of plain JDK; the existing test-scoped deps suffice.
- **Default build unaffected.** Without `GCS_TEST_ENABLED=true` the GCS tests report as skipped; the MinIO suite and CI behave exactly as before. The wire test needs no network and always runs.
- **No CI wiring in this change.** Running the GCS tests in GitHub Actions would require repository secrets and is deliberately left as a separate decision.
- **Cost**: negligible — a handful of small objects plus a 6 MB and an 11 MB upload per run, in a bucket with a 1-day delete lifecycle rule.
- **Security posture is documented, not changed**: `SECURITY.md` records that token enforcement and CORS are unsafe by default and that credentials are static and plaintext. No servlet or security defaults were altered here.

## Chosen approach, and what was rejected

| option | outcome |
|---|---|
| **Drive initiate / upload-part / complete through the library's low-level `Request` API** | **chosen** — verified end-to-end on real GCS (6 MB, 3-part, boundary, empty) and MinIO; keeps the fixed 5 MB streaming buffer |
| Upstream fix in `davidmoten/aws-lightweight-client-java` | worth doing as a follow-up, but release timing is out of our hands — 0.1.25 still carries both defects |
| Single `PUT` up to `uploadMaxSize` instead of multipart | smaller, but buffers the whole file in heap per upload |
| Replace the library (jclouds / minio-java / AWS SDK v2) | disproportionate: rewrites the module and inflates the OSGi bundle |
| Hand-concatenated XML for the complete document | worked, rejected in review as a hack — replaced by the library's public `Xml` builder |
| Add abort-on-failure, `S3_XMLNS`/`EMPTY_BODY` constants, a `withObjectMetadata` DRY helper | implemented in a first 109-line version, then removed as scope creep; failure behaviour is unchanged from the original (incomplete uploads are left to a bucket lifecycle rule) |

A one-line patch is **not** viable: `.requestBody(new byte[0])` via the helper's `transformCreateRequest` hook clears the 411, and the upload then fails at the complete step with the 400, because the helper builds that document internally with no hook.
