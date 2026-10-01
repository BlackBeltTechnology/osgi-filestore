# Google Cloud Storage as the `filestore-s3` backend — benefits, costs, and the current blocker

Decision document. `GCS_INTEROP.md` covers *how* to set it up; this one answers *should we*, and *why
it does not work today*.

**Status: works, with conditions.** Files of any size round-trip against GCS. Files under 5 MB
needed no code change; files at or above 5 MB required one production change (`putLargeFile`),
because two defects in the embedded client library made the multipart path unusable on strict
backends. That is fixed and guarded by tests. What remains before production is **security and
operational** work, not functional — see [SECURITY.md](SECURITY.md).

Every claim below was verified against a real GCS bucket (regional, europe-west3) or
by a network-free wire test. Where something is *not* verified, it says so.

---

## 1. Verdict at a glance

| question | answer |
|---|---|
| Does GCS work as an S3 backend? | **Yes**, for objects of any size |
| Was a code change needed? | **One method** — `putLargeFile`, 47 insertions / 11 deletions, for files ≥ 5 MB |
| Was the defect in GCS? | **No** — the same upload always succeeded via the AWS CLI on the same bucket |
| Was it in `filestore-s3`? | **No** — in `aws-lightweight-client-java`'s `Multipart` helper; GCS is just the first strict backend to reject it |
| Is it production-ready now? | **Functionally yes; not until the security gates are closed** — token enforcement and CORS are unsafe by default, and credentials are static and plaintext |
| Biggest remaining risk | Static HMAC credentials in plaintext config (inherent to S3 interop) |

---

## 2. Why GCS at all — the benefits

**It is a supported interface, not a hack.** GCS exposes an S3-compatible XML API ("simple
migration" mode). Google documents HMAC keys as S3-style credentials and accepts `AWS4-HMAC-SHA256`
signatures. We are using a product feature, not exploiting an accident.

**Zero code changes for the common path.** The decisive unknown was metadata. The client library
builds its metadata map by filtering response headers on the literal prefix `x-amz-meta-`, while GCS
natively emits `x-goog-*`. If GCS had returned `x-goog-meta-*`, then `getFileName()`, `getMimeType()`
and `getCreateTime()` would have returned `null` and thrown. **Measured: GCS returns `x-amz-meta-*`**
when the request is AWS-signed, so the whole metadata contract holds unmodified. This is now pinned
by a regression test rather than trusted.

**Verified working today, on a real bucket:** small-file `put`/`get` byte-exact, metadata round-trip
(`filename`, `mimetype`, `createtime`, `size`), `exists()` true and false, exactly-5 MB boundary
file, empty (0-byte) file, `activate()` genuinely building the client from config, filename
sanitisation, MIME fallback.

**Operational upside:**

- **No new dependency, no bundle growth.** The alternative of a "proper" GCS client (jclouds,
  minio-java, AWS SDK v2) would rewrite the module and inflate the OSGi bundle; AWS SDK v2 alone is
  25–50 MB with OSGi bundling still an open upstream issue. The current bundle is ~98 KB with the
  library inlined.
- **One backend, many providers.** The same `filestore-s3` code targets AWS S3, MinIO and GCS.
  Changing provider is a config change (`endpoint` + credentials), not a deployment change.
- **Durability and SLA are Google's**, unchanged by using the XML API instead of the JSON API.
- **Regional placement** (e.g. `europe-west3`) for data-residency requirements.
- **Cost control via lifecycle rules** — storage-class transitions (Standard → Nearline/Coldline)
  without touching application code.
- **Presigned URLs are possible.** Verified with the AWS CLI against GCS: unauthenticated `curl`
  → `200`, tampered signature → `403`, expired → `400`. Not used by `filestore-s3` today (see §4).

---

## 3. The one thing that had to be fixed

`put()` switches to the multipart path at `MULTIPART_THRESHOLD` (5 MB). That path delegates to the
`Multipart` helper of `aws-lightweight-client-java` (0.1.24, inlined), which deviates from the S3
specification in two ways Amazon S3 and MinIO forgive and GCS does not.

### Defect 1 — no `Content-Length` on the initiate request

The initiate request (`POST /key?uploads`) legitimately has an empty body, but the library passes
**no body at all**. `HttpClientDefault` therefore never opens the output stream, and
`HttpURLConnection` emits no `Content-Length`. GCS requires the header on every POST:

```
ServiceException: statusCode=411:
  Error 411 (Length Required)
  POST requests require a Content-length header.
```

Google documents this explicitly: *"For initiating a multipart upload, this value is 0.
Required: Yes."* A body of `new byte[0]` — empty, **not absent** — is what produces
`Content-Length: 0`.

### Defect 2 — a malformed namespace on the complete document

`MultipartOutputStream` declares `xmlns="http:s3.amazonaws.com/doc/2006-03-01/"` — note the missing
`//`, which is not a valid absolute URI. Amazon ignores it; GCS schema-validates it:

```
ServiceException: statusCode=400: MalformedCompleteMultipartUploadRequest
  element "CompleteMultipartUpload" not allowed anywhere;
  expected … xmlns:ns="http://s3.amazonaws.com/doc/2006-03-01/"
```

GCS does **not** require a namespace — it rejects only a malformed one. Both the un-namespaced form
and a correctly declared one were verified to work on GCS *and* MinIO.

### Why it was never caught before

The only large-file test targeted **MinIO**, which — like Amazon S3 — tolerates both deviations.
GCS is simply the first strict backend this code has met. The defects were always there.

### How it was resolved

`putLargeFile` now drives initiate / upload-part / complete itself through the library's public
low-level `Request` API: an empty (not absent) initiate body, and the complete document built with
the library's own public `Xml` builder with no namespace. Parts still stream through a single
reused 5 MB buffer, so the memory profile is unchanged, and no threads are created — the library's
helper used to spawn an unbounded cached thread pool per upload, so this is incidentally better.

Guarded by a network-free wire test (which fails exactly these two points against the original
code) plus 6 MB and 3-part round trips on a real bucket, with the MinIO suite unchanged.
**31 tests, 0 skipped, 0 failures.**

### What does *not* fix it

| attempt | result |
|---|---|
| Configuration / OSGi property | none exists. `MULTIPART_THRESHOLD` is `private static final`, neither defect is behind a library flag |
| Upgrading the library | 0.1.25 (latest) and `master` carry byte-identical constants — both defects still present |
| One-line patch via the helper's `transformCreateRequest` hook | **tested**: clears the 411, then fails at the complete step with the 400. The hook covers only the initiate request; `MultipartOutputStream.close()` builds the complete document internally with no hook |
| Blaming GCS | the same 6 MB upload succeeds on the same bucket via the AWS CLI, and a hand-driven initiate/part/complete sequence round-trips 6 MB |

### Impact in judo-platform terms

`uploadMaxSize` defaults to **50 MB** and is fed to `UploadServlet`. Against GCS the backend can
only store **< 5 MB**, so the servlet accepts uploads it cannot persist. Either cap uploads below
5 MB, or do not use GCS as the S3 backend, until this is fixed.

### How the fix is guarded

| test | backend | asserts |
|---|---|---|
| `…MultipartWireTest.fixedInitiateSendsEmptyBodyNotAbsentBody` | none (fake transport) | initiate body is empty, not absent → `Content-Length: 0` |
| `…MultipartWireTest.fixedCompleteDocumentIsWellFormedOrderedAndCarriesNoMalformedNamespace` | none (fake transport) | no malformed namespace, parts ascending, ETags verbatim |
| `GcsS3FileStoreServiceTest.putAndGetLargeFileViaMultipart` (+ 3-part variant) | real GCS bucket | 6 MB and 3-part round trips, byte-exact |
| `S3FileStoreServiceTest.testPutAndGetLargeFileMultipart` | MinIO | unchanged — no regression on Amazon-compatible backends |

Run against the **original** code, the wire test fails exactly the two `fixed*` tests by name and
passes the other seven — it targets these two defects and nothing else.

---

## 4. The costs — what you accept by choosing GCS-over-S3

Ordered by how much they should influence the decision.

### Significant

- **Static, long-lived credentials.** An HMAC key is a permanent secret with no rotation built in,
  and `s3SecretKey` travels as a **plaintext** configset value into ConfigAdmin. `karaf-jasypt-support`
  exists in the runtime but it is **unverified** whether decryption is wired into this config path.
  Native GCS access (workload identity, no keys at all) is not available through the S3 interop
  surface — this is inherent to the choice, not a bug. If policy forbids static keys, GCS-over-S3 is
  the wrong answer and a native backend would be needed.
- **No key prefix.** Objects are written flat at the bucket root with no configurable prefix, so it
  is **one bucket per application/environment**; a bucket cannot be shared or subdivided by IAM.
- **Silent misconfiguration.** A typo in `JUDO_PLATFORM_FILESTORE` makes `DispatcherServiceActivator`
  fall into an `else` branch that removes the filestore PID *and* both servlet PIDs with no warning —
  upload/download endpoints simply vanish. Separately, `filestore-s3` ships **no config-templates**
  (unlike filesystem/rdbms) and uses `ConfigurationPolicy.REQUIRE`, so outside the dispatcher path it
  silently never activates without a hand-dropped `.cfg`.

### Moderate

- **Interrupted uploads are never aborted.** `putLargeFile` sends no `AbortMultipartUpload` on
  failure, so a failed upload leaves an incomplete multipart upload — invisible as an object, but
  **billed as storage**. Mitigation is a bucket `AbortIncompleteMultipartUpload` lifecycle rule
  (age 7 days), not application code. (Pre-existing behaviour, not caused by anything here.)
- **No direct-download offload.** `getAccessUrl()` returns an OSGi-internal `protocol:id-name` URL,
  not a public one; downloads stream through `DownloadServlet`, so file bandwidth and CPU land on the
  Karaf node. GCS *can* do presigned URLs, but the library's `presignedUrl()` is broken on **GCS and
  MinIO alike** — it signs an empty `x-amz-content-sha256` that no browser sends. Adding real
  live-access URLs is an API + security-model decision, not a bug fix.
- **Library maturity.** Three defects were found in it during this work (initiate body, `xmlns`,
  presigned header). It is a small single-maintainer library. Mitigated by pinning the version and by
  the wire test, but it is a genuine supply-chain consideration.

### Minor

- **Bucket cannot be auto-created.** XML-API bucket creation needs an `x-goog-project-id` header the
  client never sends, so `MinioFixture`'s create-on-demand trick cannot work. The bucket is a
  provisioning precondition.
- **No integrity checksums.** GCS multipart carries no MD5 and the client sends no checksums; you
  rely on TLS. The AWS SDK would add CRC32. Low probability, non-zero.
- **`size` metadata absent on multipart uploads** — `getSize()` falls back to `Content-Length` and
  still returns the right value.
- **`region` is cosmetic.** Always sent as `us-east-1` regardless of the bucket's real location.
  Harmless for GCS SigV4, but misleading in ops.
- **AWS CLI v2 ≥ 2.23 needs two env vars** for manual probing (`SignatureDoesNotMatch` otherwise).
  Affects the CLI only, not the Java client.
- **Not soak- or concurrency-tested.** All evidence is functional and low-volume.

---

## 5. What would make it production-ready

The functional blocker is gone. What remains is configuration and operations — full detail and a
checklist in [SECURITY.md](SECURITY.md):

1. **Close the unsafe defaults.** `tokenRequired=false` and `Allow-Origin: *` with
   `Allow-Credentials: true` are the shipped defaults on both servlets; token expiry defaults to
   never. None of these are safe in production.
2. **Resolve credential handling.** Verify jasypt on this path or inject the secret from a secret
   manager; bucket-scoped service account; documented rotation.
3. **Add the `AbortIncompleteMultipartUpload` lifecycle rule** to any bucket used with this backend.
   Do **not** copy the test bucket's `Delete / age 1 day` rule — that deletes live files.
4. **Add a deployment smoke check** (upload + download one object) to catch the silent-config traps
   in [CONFIGURATION.md](CONFIGURATION.md) — notably the case-sensitive `JUDO_PLATFORM_FILESTORE`
   match that silently removes the upload/download endpoints.

Items 1 and 2 are the go-live gates; 3 and 4 are cheap and should happen regardless.

---

## 6. Bottom line

The *storage choice* is sound: GCS is a reasonable production file store, the interop API is
supported, and the metadata contract — the thing most likely to have broken — was measured working.

The *functional* state is now complete: any file size round-trips, on GCS and MinIO alike, with the
only production change confined to one method and guarded by tests that fail by name if it
regresses.

What stands between this and production is **not** the storage backend — it is the unsafe servlet
defaults and static-credential handling described in [SECURITY.md](SECURITY.md). Those apply to the
S3 backend generally, not to GCS specifically.

**Usable for production once the SECURITY.md go-live gates are closed.**

---

## References

- [SECURITY.md](SECURITY.md) — findings, severities and the hardening checklist
- [CONFIGURATION.md](CONFIGURATION.md) — every configuration surface, defaults, silent-failure traps
- [GCS_INTEROP.md](GCS_INTEROP.md) — provisioning (CLI + Console), running the test, full quirk list
- `openspec/changes/add-gcs-interop-integration-test/` — proposal, design decisions
- `src/test/java/hu/blackbelt/osgi/filestore/s3/S3FileStoreServiceMultipartWireTest.java` — wire-level guard
- `src/test/java/hu/blackbelt/osgi/filestore/s3/GcsS3FileStoreServiceTest.java` — live-bucket integration test
