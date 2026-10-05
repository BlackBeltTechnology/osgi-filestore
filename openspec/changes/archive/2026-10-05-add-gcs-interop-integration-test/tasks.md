# Tasks: add-gcs-interop-integration-test

One production change only: `S3FileStoreService.putLargeFile` (see section 3b). Everything else is test code, configuration and documentation.

## 1. Credential loading

- [x] Add `filestore-s3/src/test/java/hu/blackbelt/osgi/filestore/s3/fixture/DotEnv.java` — zero-dependency loader for `filestore-s3/.env`, path overridable via `FILESTORE_ENV_FILE`, real environment variables take precedence, blank lines and `#` comments ignored, surrounding quotes stripped
- [x] Provide `get(key)`, `get(key, default)` and `isEnabled(key)` accessors

## 2. GCS fixture

- [x] Add `filestore-s3/src/test/java/hu/blackbelt/osgi/filestore/s3/fixture/GcsFixture.java` exposing `bucketName`, `endpoint`, `accessKey`, `secretKey`, `region`, `protocol` read through `DotEnv`
- [x] Do NOT create the bucket — GCS requires `x-goog-project-id` for bucket creation over the XML API; fail with a message pointing at `docs/gcs/setup.md` instead
- [x] Add a `deleteObject(key)` helper used for per-test cleanup

## 3. Integration test

- [x] Add `filestore-s3/src/test/java/hu/blackbelt/osgi/filestore/s3/GcsS3FileStoreServiceTest.java`
- [x] Gate with `@EnabledIf("gcsEnabled")` consulting `DotEnv` (NOT `@EnabledIfEnvironmentVariable`, which cannot see the `.env` file)
- [x] In `@BeforeEach`, set ONLY `Config` values and call `activate(null, config)` — do not pre-assign `target.s3Client`, so client construction is genuinely covered
- [x] Track every created `fileId` and delete it in `@AfterEach`
- [x] Test: `activateBuildsClientFromConfig` — `s3Client` non-null, `getProtocol()` returns configured protocol
- [x] Test: `putAndGetSmallFile` — byte-exact round trip, `exists()` true
- [x] Test: `metadataRoundTripsWithAmzPrefix` — `getFileName`/`getMimeType`/`getSize`/`getCreateTime` all correct (the GCS prefix regression guard)
- [x] Test: `putAndGetLargeFileViaMultipart` — 6 MB round trip, byte-exact, `getSize` via the `Content-Length` fallback, metadata surviving assembly
- [x] Test: `putAndGetThreePartFileViaMultipart` — `2 × threshold + 1` bytes, part ordering on a real bucket
- [x] Remove the `knownLimitationLargeFileFailsOnGcs` canary — the limitation it guarded is fixed, so it is deleted rather than inverted
- [x] Test: `existsReturnsFalseForUnknownId` — `false`, no exception
- [x] Test: `getAccessUrlHasProtocolPrefixFormat` — `<protocol>:<fileId>-<fileName>`
- [x] Test: `existsAcceptsProtocolPrefixedId` — `getStrippedId` strips `<protocol>:`
- [x] Test: `fileNameIsSanitised` — special characters removed per `FilenameUtils.makeValidFilename`

## 3b. Fix multipart upload for strict S3 implementations

- [x] Establish the root cause: two spec deviations in `aws-lightweight-client-java`'s `Multipart`, both tolerated by Amazon S3 and MinIO and rejected by GCS — no `Content-Length` on the `POST ?uploads` initiate (HTTP 411), and `xmlns="http:s3.amazonaws.com/doc/2006-03-01/"`, a malformed URI, on the complete document (HTTP 400 `MalformedCompleteMultipartUploadRequest`)
- [x] Confirm it is not fixable by configuration: `MULTIPART_THRESHOLD` is a hardcoded constant, neither deviation is behind a library flag, and both are still present in the library's `master` and in released 0.1.25
- [x] Confirm GCS itself is not at fault: the same 6 MB upload succeeds against the same bucket through the AWS CLI, and a hand-driven initiate/part/complete sequence round-trips 6 MB
- [x] Confirm a one-line patch is insufficient: `.requestBody(new byte[0])` through the helper's `transformCreateRequest` hook clears the 411, then the upload fails at the complete step with the 400, because the helper builds that document internally with no hook
- [x] Prove the fix first with a throwaway probe driving the same three requests by hand against the real bucket — 6 MB round trip green; delete the probe afterwards
- [x] Verify empirically that the complete document needs no namespace at all: the un-namespaced form passes on GCS and MinIO, matching the sample request in the Amazon S3 API reference. GCS rejects only a *malformed* namespace
- [x] Replace `Multipart` in `putLargeFile` with direct initiate / upload-part / complete calls: `requestBody(new byte[0])` on initiate so `Content-Length: 0` is emitted
- [x] Build the complete document with the library's public `Xml` builder (`com.github.davidmoten.aws.lw.client.xml.builder.Xml`) rather than string concatenation; verify the class is inlined in the OSGi bundle and adds no `Import-Package`
- [x] Stream parts through a single reused `MULTIPART_THRESHOLD`-sized buffer — no full-file buffering; add a `readFully` helper so short reads still yield full-sized parts
- [x] Keep the change minimal: `putSmallFile` and every other method untouched, no new constants, no abort handling, no DRY refactor of working code. Net diff 47 insertions / 11 deletions
- [x] Confirm no memory or thread leak: 100 × 6 MB through `put()` under `-Xmx40m` peaked at 29 MB heap with 8 → 8 threads; the library's helper by contrast spawned an unbounded cached thread pool per upload

## 3c. Tests that pin the mechanism, not just the outcome

- [x] Add `S3FileStoreServiceMultipartWireTest` — no network, no container. Replaces the transport through the library's public `HttpClient` seam with a recorder and asserts the exact requests `put()` produces
- [x] Guard defect 1 as `fixedInitiateSendsEmptyBodyNotAbsentBody`: the initiate body is asserted present-but-empty, the `Content-Length: 0` mechanism
- [x] Guard defect 2 as `fixedCompleteDocumentIsWellFormedOrderedAndCarriesNoMalformedNamespace`: no malformed namespace, parts ascending, ETags verbatim
- [x] Assert the rest of the mechanism so a future change cannot regress it: parts numbered 1..n, threshold-sized with a short last part, reassembling byte-exactly (including under 1000-byte short reads), complete sent last and suppressed after a failed part, payloads ≤ 5 MB never opening multipart
- [x] Prove the wire test has teeth: run against the ORIGINAL library-based `putLargeFile` → exactly the two `fixed*` tests fail by name (`was "http:s3.amazonaws.com/doc/2006-03-01/"`), the other seven pass. Against the fix → 9 of 9
- [x] Make the wire test implementation-agnostic: parts are compared by part number, not arrival order, so a future sequential or parallel uploader is not penalised
- [x] Strengthen `putAndGetLargeFileViaMultipart`: non-uniform payload and byte-exact `assertArrayEquals` (was length-only on uniform `'x'`, which a part-reordering bug would pass), and assert `getFileName`/`getMimeType` survive multipart assembly
- [x] Add GCS tests for the shapes MinIO covered and GCS did not: three-part upload (5 MB + 5 MB + 1 byte), exactly-5 MB boundary (single-PUT path), and empty file

## 4. Configuration files

- [x] Add `filestore-s3/.env.example` with every key, placeholder values, and a comment that the real `.env` must never be committed
- [x] Append `**/.env` to the root `.gitignore`
- [x] Create a local `filestore-s3/.env` from the provisioned bucket credentials (NOT committed)

## 5. Setup documentation

- [x] Add `filestore-s3/GCS_INTEROP.md` (since split into `docs/gcs/setup.md` and `docs/gcs/multipart-fix.md`, see §9)
- [x] Section: step-by-step `gcloud` CLI commands (enable API, create bucket, lifecycle rule, service account, bucket-scoped IAM binding, HMAC key)
- [x] Section: step-by-step Cloud Console click-path for the same, including Cloud Storage → Settings → Interoperability for the HMAC key
- [x] Section: how to run the test, and what a skipped run looks like
- [x] Section: known quirks — AWS CLI v2 `SignatureDoesNotMatch`, bucket auto-create not possible on GCS, `size` metadata absent on multipart, interrupted multipart uploads never aborted (needs an `AbortIncompleteMultipartUpload` lifecycle rule), `getAccessUrl` not being a presigned URL
- [x] Section §6.1: the ≥ 5 MB defect — root cause, error output, the resolution, and the table of tests that guard it
- [x] Section §6.7: GCS *does* support S3-style presigned URLs (verified with the AWS CLI), but the library's `presignedUrl()` is broken on GCS **and** MinIO because it signs an empty `x-amz-content-sha256` that no browser sends — recorded as a third library defect, not fixed
- [x] Section §7: status and file inventory, naming the single production change
- [x] Add `SECURITY.md` — trust boundaries and 15 findings with severities, each traced to file:line (token enforcement off by default, CORS `*` with credentials, static plaintext credentials, `http://` endpoints, never-expiring tokens, ephemeral signing keys, no content scanning, weak tenant isolation, unaborted multipart uploads), plus a go-live hardening checklist and an explicit verified/unverified split
- [x] Add `CONFIGURATION.md` — both configuration paths (judo-platform `JUDO_PLATFORM_*` env vars vs. a direct `.cfg`), every property with its real default, servlet and token settings, the `.env` test path, six silent-failure traps, and a worked GCS example
- [x] Add `GCS_ASSESSMENT.md` (now `docs/gcs/assessment.md`, see §9) — benefits and costs of GCS as the backend, for the go/no-go decision

## 6. Verification

- [x] Confirm the enabled 6 MB GCS test fails with HTTP 411 before the fix (TDD red) and passes after
- [x] Confirm the wire test fails exactly 2 of 9 by name against the original code and 9 of 9 against the fix
- [x] `mvn test -pl filestore-s3 -Dtest=S3FileStoreServiceTest#testPutAndGetLargeFileMultipart` — the MinIO 6 MB multipart test green, i.e. the limitation is specific to strict backends. Docker Hub is denied on this machine (`pull access denied for minio/minio`); the image was pulled from `quay.io/minio/minio:latest` and retagged locally, no fixture change
- [x] Run the full module suite — **31 tests, 0 skipped, 0 failures** (`GcsS3FileStoreServiceTest` 12, `S3FileStoreServiceTest` 10, `S3FileStoreServiceMultipartWireTest` 9)
- [x] Confirm the only `src/main` diff is `S3FileStoreService.java`, 47 insertions / 11 deletions
- [x] Confirm `git status` shows no `.env` file as trackable

## 7. Multipart failure handling (added after review)

- [x] Add wire tests to `S3FileStoreServiceMultipartWireTest` for the four "Multipart failure handling" scenarios (200 + `<Error>` on complete, part failure aborts, failing abort is suppressed, no abort when initiate fails)
- [x] Run them against the current code and confirm they fail by name (TDD red): 3 of 4 failed; `failedInitiateSendsNoAbort` already held, since the old code sent no abort at all
- [x] In `putLargeFile`, read the complete response with `responseAsXml()` and throw when its root element is `Error`
- [x] In `putLargeFile`, wrap the part and complete steps in `try`/`catch`: on failure send `DELETE ?uploadId=…`, attach any abort failure with `addSuppressed`, and rethrow the original exception
- [x] Run `mvn test -pl filestore-s3` and confirm the wire suite is fully green and the GCS suite is still green: 35 tests, 0 failures: wire 13, GCS 12 (real bucket), MinIO 10. `minio/minio` is gone from Docker Hub and the quay.io copy is private, so `MinioFixture` now uses `cgr.dev/chainguard/minio:latest` via `asCompatibleSubstituteFor("minio/minio")` (test code only)
- [x] Confirm the `src/main` diff is still confined to `S3FileStoreService.java`

## 8. Documentation follow-up

- [x] `filestore-s3/GCS_ASSESSMENT.md`: fix the stale pre-fix wording (the subtitle "the current blocker", "it does not work today" on line 4, and the "Impact in judo-platform terms" paragraph claiming a < 5 MB limit)
- [x] `filestore-s3/SECURITY.md`: update finding S-14 (unaborted multipart uploads). The code now aborts on failure, and the lifecycle rule stays as a safety net.
- [x] `docs/gcs/multipart-fix.md` §4: change "specified, not yet implemented" to implemented (the quirk formerly in `GCS_INTEROP.md` now lives there)
- [x] `filestore-s3/SECURITY.md` S-4: downgrade and reword it to say token checks fail open only when no `TokenValidator` is bound, and that judo-platform forces `tokenRequired=true` (`DispatcherServiceActivator:264,280`, verified in judo-platform). The report and handover copies of the gate list were removed in §9, so `SECURITY.md` is the only place to change
- [x] `GCS_FILESTORE_REPORT.md` and `GCS_HANDOVER.md`: drop abort-on-failure from follow-ups, and update the test counts (obsolete: both files removed in §9)
- [x] `s3-filestore-comparison-report.md`: add a note at the top marking it historical, and point to `docs/gcs/assessment.md` §4 (formerly `GCS_FILESTORE_REPORT.md` §2.4) for the current status of each finding

## 9. Documentation reorganisation

- [x] Create `docs/gcs/` with `README.md` (verdict, decisions, reading order), `assessment.md` (benefits, costs, readiness, comparison-report history), `setup.md` (provisioning, configuration, tests, production bucket, quirks) and `multipart-fix.md` (the single copy of the defect analysis, fix, failure handling and guarding tests)
- [x] Remove `GCS_FILESTORE_REPORT.md`, `GCS_HANDOVER.md`, `filestore-s3/GCS_ASSESSMENT.md` and `filestore-s3/GCS_INTEROP.md`; branch status, test-run results and before-merge lists belong in the PR description and this file
- [x] Keep `filestore-s3/SECURITY.md` and `filestore-s3/CONFIGURATION.md` in place (they cover all S3 providers); drop their GCS-duplicated sections in favour of links
- [x] Update references in test sources, `.env.example`, `AGENTS.md` files and this change
