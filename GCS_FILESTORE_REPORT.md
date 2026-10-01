# Google Cloud Storage as the Filestore — Status Report

**Repository:** BlackBeltTechnology/osgi-filestore
**Branch:** `feature/JNG-XXXX_GcsS3InteropIntegrationTest` (pushed, **no PR yet**)
**Report date:** state as of commit `c798ac4`
**Sources:** `s3-filestore-comparison-report.md`, `filestore-s3/GCS_ASSESSMENT.md`, `filestore-s3/SECURITY.md`,
`filestore-s3/CONFIGURATION.md`, `filestore-s3/GCS_INTEROP.md`, the OpenSpec change
`add-gcs-interop-integration-test`, the current source, and a fresh test run.

---

## 1. Summary

| Question | Answer |
|---|---|
| Can GCS be used as our file store? | **Yes.** It runs through the existing `filestore-s3` module via GCS's S3-compatible API. No new module, no new dependency. |
| Does it work for all file sizes? | **Yes.** Files under 5 MB worked with no change. Files of 5 MB and above needed a fix in one method (`putLargeFile`); it is fixed and covered by tests. |
| Is it production-ready? | **Functionally yes. Security-wise, not yet.** Some servlet defaults are unsafe (no token check, open CORS, tokens that never expire) and the GCS credentials are static and stored in plaintext. These are configuration and operations tasks, not code rewrites. |
| Biggest risk specific to GCS | **Static HMAC keys.** The S3-compatible API cannot use Google's keyless workload identity. |
| Recommendation | **Go, with conditions:** close the 6 go-live security gates (§5.3) before production. |

---

## 2. Current state of the feature branch

### 2.1 Git

- 2 commits ahead of `origin/develop`. Working tree is clean except an unrelated `.pi/skills/manage-flows/`.
  - `f68d08f` regenerates the OpenSpec tooling (agent skills and commands, not product code)
  - `c798ac4` adds GCS support and fixes multipart upload
- Pushed to origin. **No pull request has been opened.**
- ⚠ The branch name and commit message use `JNG-XXXX` or no ticket. Project policy requires a real JIRA
  ticket in every commit. **A ticket needs to be assigned before the PR.**

### 2.2 What the change contains (15 files, +2419 / −11)

| Area | Content |
|---|---|
| Production code | **One method:** `S3FileStoreService.putLargeFile` (+47 / −11) plus a `readFully` helper. `putSmallFile` and the metadata contract are unchanged. |
| Tests | `S3FileStoreServiceMultipartWireTest` (9 tests, no network), `GcsS3FileStoreServiceTest` (12 tests, real bucket, opt-in via `.env`), `DotEnv` and `GcsFixture` helpers |
| Config | `filestore-s3/.env.example`; `**/.env` added to `.gitignore` (the local `.env` with real keys is **not** tracked, confirmed) |
| Docs | `GCS_INTEROP.md` (setup), `CONFIGURATION.md`, `SECURITY.md` (15 findings), `GCS_ASSESSMENT.md` (decision doc) |
| OpenSpec | `add-gcs-interop-integration-test`: proposal, design, spec, tasks. **All tasks are checked off.** The change is not archived yet. |

### 2.3 Test run today (`mvn test -pl filestore-s3`)

| Suite | Backend | Result |
|---|---|---|
| `GcsS3FileStoreServiceTest` (12) | real GCS bucket (europe-west3) | ✅ all green, none skipped |
| `S3FileStoreServiceMultipartWireTest` (9) | none (fake transport) | ✅ all green |
| `S3FileStoreServiceTest` (10) | MinIO via Testcontainers | ⚠ **could not run on this machine.** The `minio/minio` image pull was denied by Docker Hub, and the quay.io mirror returned 401. This is an environment problem, not a code failure. The branch's task log says the suite was green when the image was available. **CI should confirm it.** |

### 2.4 How the S3 module changed since the original comparison report

`s3-filestore-comparison-report.md` compared our lightweight-client implementation with an
AWS SDK v2 version. Status of each issue it raised, checked against the current code:

| Issue in the comparison report | Status now |
|---|---|
| `put()` reads the whole file into memory (`readAllBytes`) | ✅ **Fixed.** Uploads use a 5 MB buffer. Larger files stream as multipart. Peak memory is about 5 MB per upload (measured: 100 × 6 MB uploads under `-Xmx40m`, 29 MB heap peak). |
| No null check in `getStrippedId()` | ✅ **Fixed.** It now throws `IllegalArgumentException("fileId cannot be null")`. |
| `getSize()` has no fallback | ✅ **Fixed.** It falls back to `Content-Length` (needed for multipart uploads, which have no `size` metadata). |
| No `s3Client.close()` in `deactivate()` | ➖ Not applicable. The lightweight client has no pool or resources to close. |
| No `forcePathStyle(true)` | ➖ Not an issue. A custom endpoint is used as the base URL with bucket/key paths, and this works on MinIO and GCS. |
| Multipart broken on strict S3 implementations (found during GCS work) | ✅ **Fixed on this branch.** See §3. |
| Library's own `Multipart` helper started an unbounded thread pool per upload | ✅ Gone as a side effect. The helper is no longer used. |

Decision kept: we stay on the **lightweight client** (bundle about 98 KB, inlined) and do not move to
AWS SDK v2 (25–50 MB, upstream OSGi packaging still an open issue).

---

## 3. What had to be fixed for GCS (non-technical summary)

Files of 5 MB or more are uploaded in parts ("multipart"). The third-party library we use had two
small deviations from the S3 standard. Amazon S3 and MinIO tolerate them; GCS rejects them:

1. The "start upload" request had **no `Content-Length` header**, so GCS returned HTTP 411.
2. The "finish upload" XML declared a **malformed namespace** (`http:s3…` without `//`), so GCS returned HTTP 400.

Neither can be fixed through configuration or a library upgrade (both are still present in the latest
release and in `master`). We now send these three requests ourselves through the library's public API.
Tests check both defects by name: run against the old code, exactly those two tests fail.

**GCS was not at fault.** The same upload worked through the AWS CLI against the same bucket.

---

## 4. Product view: benefits and drawbacks of GCS as the filestore

### 4.1 Benefits

| Benefit | Detail |
|---|---|
| **Supported interface, not a workaround** | GCS officially offers an S3-compatible XML API with HMAC keys and SigV4 signatures |
| **No new code or dependency** | One module (`filestore-s3`) serves AWS S3, MinIO and GCS. Switching provider is a **config change** (endpoint and keys), not a release. |
| **Metadata contract holds** | The key unknown: GCS returns `x-amz-meta-*` headers for S3-signed requests, so filename, MIME type, create time and size all work. A regression test guards this. |
| **Small OSGi footprint** | The bundle stays about 98 KB. A native GCS SDK or AWS SDK v2 would add tens of MB. |
| **Scalability and durability come from Google** | No disk capacity planning on Karaf nodes, unlike the `filesystem` backend. No BLOB growth in our database, unlike the `rdbms` backend. |
| **Data residency** | Regional buckets (tested: `europe-west3`, Frankfurt) |
| **Cost control without code** | Lifecycle rules (Standard → Nearline/Coldline), Autoclass, and storage-class choice per bucket |
| **Room to grow** | GCS supports presigned URLs (verified with the AWS CLI), which could later offload downloads from Karaf |

### 4.2 Drawbacks and costs

| Drawback | Severity | Detail / mitigation |
|---|---|---|
| **Static HMAC credentials** | High | Long-lived keys with no built-in rotation. Keyless workload identity is **not available** through the S3 API. If company policy forbids static keys, a native GCS backend (new development) would be needed. |
| **Library maturity** | Medium | `aws-lightweight-client-java` has a single maintainer. We found 3 defects in it (2 fixed on our side, 1 in presigned URLs left unfixed). Mitigation: pinned version and wire tests. |
| **Downloads go through Karaf** | Medium | `getAccessUrl()` returns an OSGi-internal URL, so all download traffic and CPU passes through `DownloadServlet`. Presigned URLs would need an API and security-model decision. |
| **Abandoned multipart uploads are billed** | Medium (cost) | Failed uploads of 5 MB or more are never aborted. Mitigation: a bucket lifecycle rule `AbortIncompleteMultipartUpload` (7 days). |
| **One bucket per app/environment** | Low–Medium | No key prefix is supported, so a bucket cannot be shared or split by IAM |
| **Egress and operation costs** | Product | Google charges for network egress and per operation. Nearline/Coldline have minimum storage durations. Choose the storage class to match access patterns. |
| **Silent misconfiguration** | Medium (ops) | A typo in `JUDO_PLATFORM_FILESTORE` silently removes the filestore **and** the upload/download endpoints. The module ships no config templates. Mitigation: a smoke test at deployment. |
| **Bucket is not auto-created** | Low | It must be provisioned in advance (documented in `GCS_INTEROP.md`) |
| **No end-to-end checksums** | Low | We rely on TLS only (the AWS SDK would add CRC32) |
| **No load or concurrency testing yet** | Gap | All evidence comes from functional, low-volume testing |

---

## 5. Security view

### 5.1 What GCS gives us (bucket side; not controlled by our code)

- Encryption at rest by default (Google-managed keys). **CMEK is possible only as the bucket default.** Our client does not send encryption headers.
- Uniform bucket-level access and public-access prevention remove per-object ACL mistakes.
- Object versioning / soft delete protect against accidental or malicious deletion.
- IAM scoped to **one bucket** via a dedicated service account (`roles/storage.objectAdmin`).
- Cloud Audit Logs (Data Access logs must be enabled explicitly; they are off by default).
- Transport: HTTPS + SigV4-signed requests.

### 5.2 Findings (from `SECURITY.md`, 15 total, each traced to file:line)

Most of the risk is in **our servlet defaults**, not in GCS. These apply to every S3 provider.

| # | Finding | Severity | GCS-specific? |
|---|---|---|---|
| S-1 | Static long-lived HMAC credentials | **High** | Yes (inherent to the S3 API) |
| S-2 | Secret stored in plaintext in ConfigAdmin / `etc/*.cfg`. jasypt decryption on this path is **unverified**. | **High** | No |
| S-4 | `tokenRequired=false` by default on both servlets. Without a validator, uploads and downloads have **no authentication**. | **High** | No |
| S-5 | JWT tokens **never expire** by default (`expirationTime=0`) | **High** | No |
| S-8 | CORS `Allow-Origin: *` **with** `Allow-Credentials: true` | **High** | No |
| S-3 | An `http://` endpoint is accepted silently (cleartext) | Medium | No |
| S-6 | Signing keys are generated per node and per restart unless configured, causing random 401s behind a load balancer | Medium | No |
| S-9 | Upload limit named `_KB` but compared in **bytes** (default 50 MB) | Medium | No |
| S-10 | No virus scan or content sniffing. The MIME type is trusted from the client. | Medium | No |
| S-11 | No rate limiting or quota | Medium | No |
| S-13 | Weak tenant isolation (flat keys) | Medium | No |
| S-7, S-12, S-14, S-15 | Internal-only access URL, UUID is not authorization, unaborted multipart uploads, no checksums | Low | No |

### 5.3 Go-live gates (must be done before production)

- [ ] `tokenRequired=true` on both servlets (S-4)
- [ ] Token `expirationTime` > 0, in minutes (S-5)
- [ ] Explicit CORS origin list. Never `*` together with credentials (S-8).
- [ ] HMAC secret injected from a secret manager or Kubernetes secret, or jasypt verified on this path (S-2)
- [ ] Dedicated service account, `objectAdmin` on **one** bucket, rotation procedure documented (S-1)
- [ ] `https://` endpoint enforced (S-3)

Strongly recommended: a shared JWT signing secret for multi-node setups, the
`AbortIncompleteMultipartUpload` lifecycle rule, uniform access with public-access prevention,
one bucket per environment, explicit size limits in bytes, ingress rate limiting, and a deployment
smoke test (upload and download one file).

---

## 6. Open items before merge

1. **Assign a JIRA ticket.** Rename the branch and amend the commit message (`JNG-XXXX` is a placeholder).
2. **Open the PR** and let CI run the MinIO suite. It could not run locally because of Docker registry access.
3. **Fix stale wording in `GCS_ASSESSMENT.md`.** The subtitle ("the current blocker", "why it does not work
   today") and the §3 paragraph "Impact in judo-platform terms" ("backend can only store < 5 MB … cap
   uploads below 5 MB") describe the state **before** the fix. They contradict the rest of the document
   and could mislead a reviewer.
4. **Mark `s3-filestore-comparison-report.md` as historical.** Most of its findings are resolved (§2.4).
5. Archive the OpenSpec change `add-gcs-interop-integration-test` after merge (all tasks are done).
6. Optional follow-ups (separate tickets): abort multipart uploads on failure in code, a configurable key
   prefix, a load/concurrency test, and a decision on presigned download URLs.

---

## 7. Bottom line

GCS is a **sound choice of storage** for the filestore. The integration is complete, small (one method
changed), tested against a real bucket, and needs no new dependency. **The blockers are security
configuration, not the backend:** unsafe servlet defaults that apply to every provider, plus static HMAC
credentials, which are the one real trade-off of using GCS through its S3 API. Once the six go-live gates
in §5.3 are closed, it is ready for production.
