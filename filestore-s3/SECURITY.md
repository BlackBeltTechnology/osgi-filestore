# Security considerations — `filestore-s3` (AWS S3 / MinIO / Google Cloud Storage)

Scope: the whole request path a stored file travels — browser → `UploadServlet` / `DownloadServlet`
→ `FileStoreService` → bucket — because most of the risk lives at the edges, not in the S3 client.

Every default quoted here was read out of the source; file and line are given so it can be checked.
Where something is **not** verified, it says so rather than guessing.

> **The two defaults most likely to surprise you:** token enforcement is **off**
> (`tokenRequired() default false`), and CORS is `Access-Control-Allow-Origin: *` **together with**
> `Allow-Credentials: true`. Both servlets. See S-4 and S-8.

---

## 1. Trust boundaries

| boundary | what crosses it | control |
|---|---|---|
| Browser → servlet | file bytes, filename, MIME, `X-Token` | JWT (optional, off by default), size limits, CORS |
| Servlet → `FileStoreService` | validated stream + metadata | in-process, no auth |
| `FileStoreService` → bucket | HTTPS + SigV4 signed requests | HMAC/IAM credentials |
| Bucket → anyone | nothing by default | bucket IAM, uniform access, public-access prevention |

`getAccessUrl()` does **not** cross a trust boundary: it returns `protocol:<fileId>-<name>`, an
OSGi-internal URL, not a public link (S-7).

---

## 2. Findings

Severity is *for a typical judo-platform deployment*: **High** = exploitable or exposes data with
default config; **Medium** = needs a second condition; **Low** = hardening / defence in depth.

### S-1 · Static, long-lived cloud credentials — **High** (inherent to S3 interop)

`Config.accessKey()` / `secretKey()` (`S3FileStoreService.java:47,50`) are permanent secrets with no
built-in rotation and no expiry. On GCS these are **HMAC keys**; the S3 interop API offers no
workload-identity/keyless equivalent, so this cannot be engineered away while using this backend —
only contained.

- Use a **dedicated service account**, never a user or a default SA.
- Grant **`roles/storage.objectAdmin` on the single bucket**, never project-wide, never
  `storage.admin` (that would allow bucket deletion and IAM changes).
- Rotate on a schedule. GCS supports two active HMAC keys per SA, so rotation can be zero-downtime:
  create new → deploy → deactivate old → delete old.
- Revocation is the only containment if a key leaks; there is no per-object or per-session scoping.

### S-2 · The secret travels and rests in plaintext — **High**

`s3SecretKey` is passed as an ordinary configset/`.cfg` value into ConfigAdmin. That means it is
readable in `etc/*.cfg` on disk, in ConfigAdmin's own persisted store, and via Karaf's
`config:list` / web console to any operator with shell or console access.

`karaf-jasypt-support` exists in the runtime, but **it is unverified whether jasypt decryption is
wired into this specific config path** — do not assume `ENC(...)` works here until tested.

- Inject from a secret manager / Kubernetes secret rather than committing to a configset.
- Restrict filesystem permissions on `etc/` and access to the Karaf console.
- Verify jasypt end-to-end before relying on it.

**Not** a finding: `S3FileStoreService` contains **no logging statements at all** (verified — no
`log.*` calls in the class), so it does not leak the key to logs itself. Karaf config dumps still can.

### S-3 · `http://` endpoints silently disable transport security — **Medium**

`Config.endpoint()` is a free-form string (`:44`). Nothing validates the scheme, so a typo or a
copy-pasted MinIO dev endpoint (`http://…`) sends **SigV4-signed requests and file bytes in
cleartext**. The signature still validates; only confidentiality is lost, so there is no error to
notice.

- Always `https://storage.googleapis.com` for GCS; plain `http` only for a local MinIO container.
- Consider asserting the scheme in your deployment smoke check.

### S-4 · Token enforcement is **off by default** — **High**

`tokenRequired() default false` on **both** servlets (`UploadServlet.java:82`,
`DownloadServlet.java:71`). The logic is:

```java
if (tokenRequired && tokenValidator == null) { /* fail */ }
if (tokenValidator != null) { /* validate X-Token */ }
```

So: if **no** `TokenValidator` service is registered *and* `tokenRequired` is false, upload and
download are **completely unauthenticated** — anyone who can reach the endpoint can store and
retrieve files. A validator being present does cause validation even when not required, which is the
saving grace in judo-platform, where the security feature is normally installed.

- Set `tokenRequired=true` explicitly in production. Do not rely on the validator merely existing.

### S-5 · Tokens never expire by default — **High** (with S-4)

`expirationTime() default 0` = "not expiring" (`TokenServiceConfig.java:39-40`). The issuer only
calls `setExpirationTimeMinutesInTheFuture` and the validator only calls `setRequireExpirationTime`
when the value is `> 0`. A leaked download token is therefore valid **forever**, and there is no
revocation mechanism.

- Set `filestoreTokenExpiry` / `expirationTime` to the smallest workable value (minutes).

### S-6 · Ephemeral signing keys by default — **Medium**

When no `secret` is configured, `DefaultKeyProvider` generates a key at activation
(`SecureRandom`, `RsaJwkGenerator`/`EcJwkGenerator`, `DefaultKeyProvider.java:77,98,118`).
Consequences: every restart invalidates all outstanding tokens, and in a **multi-node deployment
each node signs with a different key**, so tokens are not portable across nodes — intermittent 401s
behind a load balancer.

- Configure an explicit, shared `secret` (or key pair) in any clustered or restart-sensitive setup.

### S-7 · `getAccessUrl()` is not an access-control boundary — **Low** (but often misread)

It returns `protocol:<fileId>-<fileName>` with no signature and no expiry, resolvable only inside
the OSGi runtime via the registered `URLStreamHandler`. It is **not** a presigned URL and not
shareable. Public links must go through `DownloadServlet` + JWT.

Related, verified: GCS *does* support S3-style presigned URLs (AWS CLI: unauthenticated `curl`
→ 200, tampered → 403, expired → 400), but the client library's `presignedUrl()` is broken on **GCS
and MinIO alike** (it signs an empty `x-amz-content-sha256` no browser sends). If presigned URLs are
ever added, note they **bypass** `DownloadServlet` — and with it the JWT check, CORS handling and
`Content-Disposition` — and move download audit from the application to bucket logs.

### S-8 · CORS: wildcard origin **with** credentials — **High**

Defaults on both servlets: `cors_allowOrigin() default "*"` and
`cors_allowCredentials() default true` (`UploadServlet.java:85,88`, `DownloadServlet.java:74,77`).

Allowing credentials with a wildcard origin is precisely the combination the CORS spec forbids
browsers to honour; depending on how the filter echoes the origin, this can permit **any** website
to drive authenticated upload/download requests with the victim's cookies. Even where the browser
blocks it, the configuration signals an intent that is unsafe.

- Set an explicit comma-separated origin list. Keep `allowCredentials=true` only if cookies are
  genuinely used, and never together with `*`.

### S-9 · Upload size limits: misleading units — **Medium**

`maxSize` / `maxFileSize` default to `Constants.DEFAULT_REQUEST_LIMIT_KB = 50 * 1024 * 1024`
(`Constants.java:63`) — but the value is compared **directly against `Content-Length` in bytes**
(`UploadServlet.java:187`), with no `× 1024`. So despite the `_KB` name and the "(kB)" attribute
label, the effective default is **50 MB, not 50 GB**.

The hazard is in the other direction: someone "correcting" the units by setting `maxSize=51200`
(intending 50 MB) would actually impose a **50 kB** limit. Treat both values as **bytes**.

### S-10 · No content inspection — **Medium** (accepted by design)

Uploads are not virus-scanned, and the MIME type is taken from the client or guessed from the file
extension — never from content sniffing. A file stored as `image/png` may be anything.

`FilenameUtils.makeValidFilename` strips characters like `$()[];#@~,&'` but **deliberately keeps
spaces**; it is a filesystem-safety measure, **not** an XSS or path-traversal defence.

- Never serve user-uploaded content from the application's own origin without
  `Content-Disposition: attachment` and a restrictive CSP.
- Add AV scanning upstream if untrusted users can upload.

### S-11 · No rate limiting or quota — **Medium**

Nothing limits upload frequency, concurrency or total bytes per caller. With S-4 (auth off) this is
a direct storage-cost and availability risk. `slowUploads` and `noDataTimeout` (default 20 s) shape
slow clients but are not a quota.

- Rate-limit at the ingress/API gateway.

### S-12 · Object identifiers are unguessable, but that is not authorization — **Low**

File IDs are 32-char dashless UUIDs, so enumeration is impractical. That is *obscurity*: anyone who
learns an ID (logs, referrer, shared link) can fetch the object wherever authentication is absent.
Do not treat the ID as a capability.

### S-13 · Weak tenant isolation inside a bucket — **Medium**

Objects are written **flat at the bucket root** with no configurable key prefix, so one bucket
cannot be subdivided per tenant/application by IAM condition on object prefix.

- **One bucket per application per environment.** Do not share a bucket between deployments.

### S-14 · Interrupted multipart uploads are never aborted — **Low** (cost, not confidentiality)

`putLargeFile` sends no `AbortMultipartUpload` when a part fails, so a failed ≥ 5 MB upload leaves
an incomplete multipart upload: invisible as an object, but **billed as storage**, indefinitely.

- Set an `AbortIncompleteMultipartUpload` lifecycle rule (age 7 days) on every bucket used with this
  backend. Do **not** copy the test bucket's `Delete / age 1 day` rule — that deletes live files.

### S-15 · No end-to-end integrity check — **Low**

GCS multipart carries no MD5, and the client sends no checksums, so corruption detection relies on
TLS alone. The AWS SDK would add CRC32. Low probability, non-zero.

---

## 3. Bucket-side configuration (not controlled by this code)

- **Uniform bucket-level access: on.** The component never sends ACLs; uniform access removes a
  whole class of per-object ACL mistakes.
- **Public access prevention: enforced**, unless a public bucket is a deliberate requirement.
- **Encryption at rest** is on by default with Google-managed keys. **CMEK is not wired into this
  backend** — the client sends no `x-amz-server-side-encryption*` headers — so if you need
  customer-managed keys, configure them as the **bucket default**, not per request.
- **Object versioning / soft delete** protects against accidental or malicious deletion; neither is
  enabled by this code.
- **Audit logging**: GCS Data Access logs are off by default. With downloads proxied through
  `DownloadServlet` the application is the primary audit point — but only if you log there.

---

## 4. Hardening checklist

Go-live gates:

- [ ] `tokenRequired=true` on both servlets (S-4)
- [ ] `expirationTime` > 0, set to minutes not hours (S-5)
- [ ] Explicit CORS origin list; never `*` with `allowCredentials=true` (S-8)
- [ ] Secret injected from a secret manager; jasypt verified if used (S-2)
- [ ] Service account scoped to `objectAdmin` on **one** bucket; rotation documented (S-1)
- [ ] `endpoint` is `https://` (S-3)

Strongly recommended:

- [ ] Shared, explicit signing `secret` if more than one node (S-6)
- [ ] `AbortIncompleteMultipartUpload` lifecycle rule (S-14)
- [ ] Uniform bucket-level access + public access prevention
- [ ] One bucket per application/environment (S-13)
- [ ] `maxSize` / `maxFileSize` set explicitly, in **bytes** (S-9)
- [ ] Rate limiting at the ingress (S-11)
- [ ] Deployment smoke check: upload + download one object (catches the silent-config traps in
      [CONFIGURATION.md](CONFIGURATION.md))

---

## 5. Verified vs. unverified

**Verified by reading the source** (file:line given above): all defaults, the token-check logic, the
absence of logging in `S3FileStoreService`, the byte-vs-kB comparison, key generation.

**Verified empirically against a real bucket:** metadata round-trip over the S3 interop API,
presigned-URL support on GCS and the library's broken `presignedUrl()`.

**Explicitly unverified — do not assume:**

- whether jasypt `ENC(...)` decryption is wired into the filestore config path (S-2);
- behaviour under concurrency or sustained load — no soak or load testing was done;
- whether any deployment actually sets `tokenRequired`; this document describes the **code
  defaults**, not your environment.

---

## References

- [CONFIGURATION.md](CONFIGURATION.md): every configuration surface, defaults, and the silent-failure traps
- [docs/gcs/setup.md](../docs/gcs/setup.md): provisioning, production bucket, GCS quirks
- [docs/gcs/multipart-fix.md](../docs/gcs/multipart-fix.md): the multipart defect, its fix and failure handling
- [docs/gcs/assessment.md](../docs/gcs/assessment.md): benefits and costs of GCS as the backend
