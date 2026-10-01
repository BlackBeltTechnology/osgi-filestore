# Design: add-gcs-interop-integration-test

## Context

`S3FileStoreService` talks to S3 through `com.github.davidmoten:aws-lightweight-client-java:0.1.24`, which signs with AWS Signature V4 and sends user metadata as `x-amz-meta-KEY` headers. Google Cloud Storage exposes an S3-compatible XML API ("simple migration" mode) that accepts `x-amz-*` headers when the `Authorization` header uses the `AWS4-HMAC-SHA256` identifier with a GCS HMAC key.

## Key decision: which prefix does GCS return?

This was the only real unknown and it decides whether any production code change is needed.

Google documents that it *accepts* `x-amz-*` on write; it does **not** document which prefix it returns on `HEAD`. Empirically verified against a real GCS bucket:

```
'x-goog-generation': '1789561660810995',        <- GCS-native headers keep x-goog-
'x-goog-metageneration': '1',
'x-amz-meta-filename': 'probe.txt',             <- CUSTOM METADATA comes back as x-amz-meta-
'x-amz-meta-mimetype': 'text/plain',
'x-amz-meta-createtime': '1700000000000',
'x-amz-meta-size': '4',
```

`Response.metadata()` filters exactly `x-amz-meta-`, therefore **no production change is required**. Because this is an undocumented behaviour of a third party, it is pinned by an explicit test rather than left to chance.

## Decision: opt-in gating via `@EnabledIf`, not `@EnabledIfEnvironmentVariable`

`@EnabledIfEnvironmentVariable` only reads real process environment variables, which would defeat the requirement to keep configuration in a `.env` file. A static `gcsEnabled()` predicate consulting `DotEnv` is used instead, so a developer only needs to drop in `filestore-s3/.env`.

## Decision: name it `...Test`, not `...IT`

The reactor configures surefire only; no failsafe plugin exists. Naming the class `GcsS3FileStoreServiceTest` keeps it in the normal `mvn test` lifecycle while the `@EnabledIf` gate makes it a no-op for everyone without credentials. Introducing failsafe would be a build-infrastructure change out of scope for this proposal.

## Decision: do not pre-inject `s3Client`

`S3FileStoreServiceTest` (MinIO) assigns `target.s3Client` before `activate()`, which skips client construction entirely. The GCS test deliberately supplies only `Config` values and lets `activate()` build the client, so the `endpoint`/`baseUrlFactory` trailing-slash logic and credential wiring are genuinely covered.

## Decision: the bucket is a precondition, not created by the fixture

`MinioFixture.setupMinio()` creates its bucket with a bare `PUT` on the bucket path. That cannot work on GCS — bucket creation over the XML API requires an `x-goog-project-id` header the client does not send. `GcsFixture` therefore asserts the bucket is reachable and fails with an actionable message pointing at `GCS_INTEROP.md`.

## Decision: fix the ≥ 5 MB defect here, after explicitly widening scope

Probing turned up a hard failure: files at or above `MULTIPART_THRESHOLD` could not be stored on GCS, because `aws-lightweight-client-java`'s `Multipart` helper omits `Content-Length` on the initiate request (GCS: 411) and declares a malformed `xmlns` on the complete document (GCS: 400). Fixing it means replacing the helper in `putLargeFile` — a production change, in a change whose original scope was test coverage only.

The change was first delivered as documentation plus characterisation tests, with `src/main` untouched. That version was complete and coherent: the defect was pinned by a network-free wire test, a canary asserting the live 411, and `@Disabled` tests expressing the desired behaviour. Scope was then **explicitly widened** to include the fix, because shipping a backend that silently cannot store a 6 MB file — while judo-platform's `uploadMaxSize` defaults to 50 MB — is a worse outcome than a slightly broader change.

What kept the widened scope honest:

- **The fix is confined to one method.** `putLargeFile` plus a `readFully` helper; 47 insertions / 11 deletions. `putSmallFile`, the metadata contract and everything else are byte-identical.
- **Only public API of the dependency is used** — `Request`, `responseAsXml`, `responseExpectStatusCode`, `firstHeader`, and the library's own public `Xml` builder. No reflection, no forked classes, no hand-written XML.
- **It is the S3 low-level multipart API as Amazon documents it**, not a GCS special case: there is no `if (isGcs)` anywhere, which is why MinIO accepts it unchanged.
- **The tests were written to fail against the old code**, and verified to do so by name, so they guard the defect rather than restate the implementation.
- **Rejected as scope creep** within the fix itself: abort-on-failure handling, `S3_XMLNS`/`EMPTY_BODY` constants, and a DRY refactor of `putSmallFile` (which was never broken). Failure behaviour is therefore unchanged from the original — an interrupted upload leaves an incomplete multipart upload for a bucket lifecycle rule to reap.

## Decision: add multipart failure handling after review (reverses the abort exclusion above)

Review found two gaps that the hand-driven multipart path now owns, because the library helper that might have covered them is no longer used:

1. **A 200 response can carry an error.** Amazon documents that `CompleteMultipartUpload` may answer HTTP 200 with an `<Error>` document when assembly fails after the response has started. The library decides success from the HTTP status alone: `Request.responseAsBytes()` throws only when the client's `ExceptionFactory` rejects the response, which by default means a non-2xx status, and `execute()` discards the body. So `put()` could return a `fileId` for an object that does not exist. The fix reads the complete response with `responseAsXml()` and throws when the root element is `Error`, using the library's public API only.
2. **Abort on failure.** Earlier this was rejected as scope creep. It is reversed here because the cost is small and contained (a `try`/`catch` around the part and complete steps, one `DELETE ?uploadId=…`), and because without it every failed upload of 5 MB or more is billed until a bucket rule reaps it.

Rules for the abort:

- It is **best-effort**. The original exception is always the one propagated. If the abort itself fails, its exception is attached with `addSuppressed` and never replaces the original.
- It runs only after a successful initiate, because before that there is no `uploadId` to abort.
- The `AbortIncompleteMultipartUpload` bucket lifecycle rule **stays recommended** as a safety net for cases code cannot cover (JVM crash, network partition during the abort).

Both are pinned by wire tests using the existing recording `HttpClient` fake, so no network is needed. Failure semantics for small files (`putSmallFile`) are unchanged.

## Known third-party quirks recorded for future readers

1. **AWS CLI v2 >= 2.23 fails against GCS** with `SignatureDoesNotMatch` because it injects CRC32 checksums and `aws-chunked` payload signing. Workaround for manual probing: `AWS_REQUEST_CHECKSUM_CALCULATION=when_required`. This affects only the CLI; `aws-lightweight-client-java` performs plain SigV4 and is unaffected.
2. **`putLargeFile()` does not write `META_SIZE`** (`S3FileStoreService.java:180-190`), unlike `putSmallFile()`. `getSize()` survives via the `__content-length__` fallback (`:239-242`). The test asserts this explicitly so the asymmetry is documented behaviour rather than a latent surprise.

## Alternatives considered

- **Testcontainers "fake GCS server"** — rejected: fake-gcs-server implements the JSON API, not the S3 XML interop surface, so it would prove nothing about the prefix question.
- **Patching `getObjectMetadata()` to accept both prefixes defensively** — rejected for now: it would be untested speculation against the measured behaviour, and adds a branch nothing exercises. Revisit only if the guard test starts failing.
- **Leaving the defect documented but unfixed** — this was the delivered state for one iteration and is a defensible outcome, but it leaves the backend unable to store a file the servlet accepts. Rejected once scope was widened.
- **A one-line patch via the helper's `transformCreateRequest` hook** — rejected because it does not work: tested, it clears the 411 and then fails at the complete step with the 400.
- **Upgrading the library instead** — rejected: 0.1.25 and `master` carry byte-identical constants. An upstream PR remains worth filing, but cannot unblock this.
