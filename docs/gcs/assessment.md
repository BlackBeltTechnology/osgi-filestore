# Google Cloud Storage as the filestore: benefits, costs, readiness

The go/no-go document. *How* to set it up is in [setup.md](setup.md); the security findings are in
[SECURITY.md](../../filestore-s3/SECURITY.md).

Every claim below was verified against a real GCS bucket (regional, `europe-west3`) or by a
network-free wire test. Where something is *not* verified, it says so.

---

## 1. Benefits

**A supported interface, not a hack.** GCS officially offers an S3-compatible XML API. Google
documents HMAC keys as S3-style credentials and accepts `AWS4-HMAC-SHA256` signatures.

**The metadata contract holds.** This was the decisive unknown. The client library builds its
metadata map from response headers with the literal prefix `x-amz-meta-`, while GCS natively emits
`x-goog-*`. Had GCS returned `x-goog-meta-*`, `getFileName()`, `getMimeType()` and `getCreateTime()`
would have broken. **Measured: GCS returns `x-amz-meta-*` for AWS-signed requests**, so filename,
MIME type, create time and size all work. A regression test pins this.

**Verified on a real bucket:** small-file `put`/`get` byte-exact, metadata round trip, `exists()`
true and false, the exactly-5 MB boundary, a 0-byte file, 6 MB and 3-part multipart uploads,
`activate()` building the client from config, filename sanitisation, MIME fallback.

| Benefit | Detail |
|---|---|
| **No new code or dependency** | One module (`filestore-s3`) serves AWS S3, MinIO and GCS. Switching provider is a **config change** (endpoint and keys), not a release. The only production change for GCS was one method ([multipart-fix.md](multipart-fix.md)). |
| **Small OSGi footprint** | The bundle stays about 98 KB with the client library inlined. AWS SDK v2 alone is 25–50 MB, and its OSGi packaging is still an open upstream issue. |
| **Scalability and durability are Google's** | No disk capacity planning on Karaf nodes (unlike `filesystem`), no BLOB growth in our database (unlike `rdbms`). |
| **Data residency** | Regional buckets (tested: `europe-west3`, Frankfurt). |
| **Cost control without code** | Lifecycle rules (Standard → Nearline/Coldline), Autoclass, storage class per bucket. |
| **Room to grow** | GCS supports presigned URLs (verified with the AWS CLI), which could later offload downloads from Karaf ([setup.md §7.5](setup.md#75-presigned-urls-gcs-supports-them-the-library-does-not)). |

---

## 2. Costs and limitations

Ordered by how much they should influence the decision.

| Drawback | Severity | Detail / mitigation |
|---|---|---|
| **Static HMAC credentials** | High | Permanent keys with no built-in rotation, stored in plaintext config. Keyless workload identity is **not available** through the S3 API; this is inherent to the choice. If policy forbids static keys, a native GCS backend (new development) is needed. SECURITY.md S-1, S-2. |
| **Silent misconfiguration** | Medium (ops) | A typo in `JUDO_PLATFORM_FILESTORE` silently removes the filestore **and** the upload/download endpoints, and without config the component never starts. Mitigation: a deployment smoke test. [CONFIGURATION.md §6](../../filestore-s3/CONFIGURATION.md#6-silent-failure-traps). |
| **One bucket per app/environment** | Medium | Objects are written flat with no configurable key prefix, so a bucket cannot be shared or split by IAM. SECURITY.md S-13. |
| **Library maturity** | Medium | `aws-lightweight-client-java` has a single maintainer. Three defects were found in it (two fixed on our side, the presigned-URL one left unfixed). Mitigation: pinned version and wire tests. |
| **Downloads go through Karaf** | Medium | `getAccessUrl()` returns an OSGi-internal URL, so download bandwidth and CPU land on `DownloadServlet`. Presigned URLs would need an API and security-model decision. |
| **Abandoned multipart uploads are billed** | Medium (cost) | Until abort-on-failure lands ([multipart-fix.md §4](multipart-fix.md#4-failure-handling)), a failed ≥ 5 MB upload stays billed. Keep the `AbortIncompleteMultipartUpload` lifecycle rule either way. |
| **Egress and operation costs** | Product | Google charges for egress and per operation; Nearline/Coldline have minimum storage durations. Choose the class to match access patterns. |
| **Bucket is not auto-created** | Low | Provision it in advance ([setup.md §7.1](setup.md#71-the-bucket-cannot-be-created-by-the-client)). |
| **No end-to-end checksums** | Low | Relies on TLS only; the AWS SDK would add CRC32. SECURITY.md S-15. |
| **`region` is cosmetic** | Low | Always signed as configured (default `us-east-1`), not matched against the bucket. Misleading in ops only. |
| **No load or concurrency testing** | Gap | All evidence is functional and low-volume. |

---

## 3. What makes it production-ready

Functionally it is complete: any file size round-trips on GCS and MinIO. What remains is
configuration and operations, and most of it applies to **every** S3 provider, not GCS
specifically:

1. **Close the go-live gates** in [SECURITY.md §4](../../filestore-s3/SECURITY.md#4-hardening-checklist):
   token enforcement and expiry, an explicit CORS origin list, secret handling, a bucket-scoped
   service account with rotation, and an `https://` endpoint. The OpenSpec change
   `harden-servlet-defaults` makes the token-expiry and CORS defaults safe in code.
2. **Add the `AbortIncompleteMultipartUpload` lifecycle rule** to every bucket
   ([setup.md §6](setup.md#6-production-bucket)).
3. **Add a deployment smoke check** (upload and download one file).

---

## 4. History: the original comparison report

`s3-filestore-comparison-report.md` (repository root) compared our lightweight-client
implementation with an AWS SDK v2 version. Status of each issue it raised, checked against the
current code:

| Issue in the comparison report | Status now |
|---|---|
| `put()` reads the whole file into memory (`readAllBytes`) | ✅ Fixed. Uploads use a 5 MB buffer and larger files stream as multipart (measured: 100 × 6 MB uploads under `-Xmx40m`, 29 MB heap peak). |
| No null check in `getStrippedId()` | ✅ Fixed. Throws `IllegalArgumentException("fileId cannot be null")`. |
| `getSize()` has no fallback | ✅ Fixed. Falls back to `Content-Length` (needed for multipart uploads). |
| No `s3Client.close()` in `deactivate()` | ➖ Not applicable. The lightweight client holds no pool or resources. |
| No `forcePathStyle(true)` | ➖ Not an issue. The custom endpoint is used as base URL with bucket/key paths; works on MinIO and GCS. |
| Multipart broken on strict S3 implementations | ✅ Fixed ([multipart-fix.md](multipart-fix.md)). |
| Library `Multipart` helper started an unbounded thread pool per upload | ✅ Gone; the helper is no longer used. |

Decision kept: stay on the **lightweight client** rather than AWS SDK v2.
