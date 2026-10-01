# Running `filestore-s3` against Google Cloud Storage

Google Cloud Storage exposes an S3-compatible XML API ("simple migration" mode). `S3FileStoreService`
works against it for files of any size, because GCS accepts `x-amz-*` request headers and returns
custom object metadata under the `x-amz-meta-` prefix that `aws-lightweight-client-java` expects.

Files under 5 MB needed no code change at all. Files ≥ 5 MB take the multipart path, which required
**one production change** — `putLargeFile` no longer uses the client library's `Multipart` helper,
because that helper is rejected by GCS for two reasons Amazon S3 and MinIO forgive. Root cause,
evidence and the fix are in [section 6.1](#61-files--5-mb--why-multipart-is-not-used-fixed).

This document covers provisioning (CLI and Cloud Console), configuration, running the opt-in
integration test, and the known limitations. See also:
[CONFIGURATION.md](CONFIGURATION.md) for every configuration surface and its traps,
[SECURITY.md](SECURITY.md) for the security posture and hardening checklist, and
[GCS_ASSESSMENT.md](GCS_ASSESSMENT.md) for the benefits/costs decision.

---

## 1. What you need

| | |
|---|---|
| A GCS bucket | created up front — the test does **not** create it |
| A service account | with `roles/storage.objectAdmin` **on that bucket** |
| An HMAC key for that service account | the access ID / secret become `accessKey` / `secretKey` |

> The HMAC key is **not** a service-account JSON key. It is created under
> *Cloud Storage → Settings → Interoperability*.

---

## 2. Provisioning — `gcloud` CLI

```bash
export PROJECT=<your-project-id>
export BUCKET=<globally-unique-bucket-name>
export REGION=europe-west3
export SA=filestore-s3-test
export SA_EMAIL=$SA@$PROJECT.iam.gserviceaccount.com

gcloud config set project $PROJECT

# 1. Storage API (usually already on)
gcloud services enable storage.googleapis.com

# 2. Bucket. Uniform access matters: the component never sends ACLs.
gcloud storage buckets create gs://$BUCKET \
  --location=$REGION \
  --uniform-bucket-level-access \
  --default-storage-class=STANDARD

# 3. Optional but recommended for a test bucket: delete objects after 1 day
cat > /tmp/lifecycle.json <<'EOF'
{"lifecycle":{"rule":[{"action":{"type":"Delete"},"condition":{"age":1}}]}}
EOF
gcloud storage buckets update gs://$BUCKET --lifecycle-file=/tmp/lifecycle.json

# 4. Service account, with NO project-level role
gcloud iam service-accounts create $SA --display-name="osgi-filestore s3 interop test"

# 5. Grant it object access on THIS BUCKET ONLY
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/storage.objectAdmin"

# 6. HMAC key -> the S3 credential pair. The secret is shown ONCE.
gcloud storage hmac create $SA_EMAIL
```

If step 6 is denied, grant yourself the HMAC admin role first:

```bash
gcloud projects add-iam-policy-binding $PROJECT \
  --member="user:<you>@<domain>" --role="roles/storage.hmacKeyAdmin"
```

---

## 3. Provisioning — Cloud Console (user-creation steps)

Keep the project selector pinned to your project on every page.

1. **Billing** — `console.cloud.google.com/billing` → *Link a billing account*.
   A project without billing cannot create a bucket.

2. **Enable the API** — ☰ *APIs & Services → Library* → search **Cloud Storage API** → **Enable**.

3. **Create the bucket** — ☰ *Cloud Storage → Buckets* → **Create**
   - *Get started*: globally unique name
   - *Choose where to store your data*: **Region**, e.g. `europe-west3`
   - *Choose a storage class*: **Standard**
   - *Choose how to control access*: Access control = **Uniform**
   - **Create**
   - Optional: open the bucket → *Lifecycle* → **Add a rule** → *Delete object* / *Age = 1 day*

4. **Create the service account** — ☰ *IAM & Admin → Service Accounts* → **Create service account**
   - Name `filestore-s3-test`
   - **Skip** the "Grant this service account access to project" step
   - **Done** — and do **not** create a JSON key here

5. **Grant bucket access** — ☰ *Cloud Storage → Buckets* → your bucket → **Permissions** tab →
   **Grant access**
   - *New principals*: the service-account e-mail
   - *Role*: **Cloud Storage → Storage Object Admin**
   - **Save**

6. **Create the HMAC key** — ☰ *Cloud Storage → **Settings*** → **Interoperability** tab
   - Click **Enable interoperability access** if the button is shown
   - Under *Access keys for service accounts* → **Create a key for a service account**
   - Pick the service account → **Create key**
   - Copy **Access key** (`GOOG1E…`) and **Secret** — the secret is displayed only once
   - If the button is greyed out, give yourself *IAM → Storage HMAC Key Admin*

---

## 4. Configuration

Copy `.env.example` to `.env` in this module and fill it in:

```bash
cp filestore-s3/.env.example filestore-s3/.env
chmod 600 filestore-s3/.env
```

```properties
GCS_TEST_ENABLED=true
GCS_BUCKET_NAME=<your bucket>
GCS_ENDPOINT=https://storage.googleapis.com
GCS_ACCESS_KEY=GOOG1E...
GCS_SECRET_KEY=<the secret>
GCS_REGION=us-east-1
GCS_PROTOCOL=gcsstore
```

`**/.env` is git-ignored. **Never commit it.** Real environment variables take precedence over
the file, so CI can inject the same keys as secrets. A different path can be given with
`FILESTORE_ENV_FILE=/path/to/file`.

### Karaf runtime configuration

The same values, as `etc/hu.blackbelt.osgi.filestore.s3.S3FileStoreService.cfg`:

```properties
protocol   = gcsstore
bucketName = <your bucket>
endpoint   = https://storage.googleapis.com
accessKey  = GOOG1E...
secretKey  = <the secret>
region     = us-east-1
```

Then `feature:install filestore-s3`.

---

## 5. Running the test

```bash
# with .env present -> runs against the real bucket
mvn test -pl filestore-s3 -am -Dtest=GcsS3FileStoreServiceTest -Dsurefire.failIfNoSpecifiedTests=false

# without .env -> every GCS test is skipped, nothing else changes
FILESTORE_ENV_FILE=/nonexistent.env mvn test -pl filestore-s3 -am
```

Expected when enabled: **12 passed, 0 skipped**.

Expected when not configured: **12 skipped**, build unaffected — the wire test
(`S3FileStoreServiceMultipartWireTest`, 9 tests) needs no network and always runs.

---

## 6. Known limitations and quirks

### 6.1 Files ≥ 5 MB — why `Multipart` is not used (FIXED)

`put()` switches to the multipart path at `MULTIPART_THRESHOLD` (5 MB). The `Multipart` helper of
`aws-lightweight-client-java` cannot be used against GCS, because it deviates from the S3
specification in two ways that Amazon S3 and MinIO forgive and GCS does not.

**1. No `Content-Length` on the initiate request.** The initiate request legitimately has an empty
body, but the library passes no body at all, so `HttpClientDefault` never opens the output stream
and the JDK emits no `Content-Length`:

```
ServiceException: statusCode=411:
  Error 411 (Length Required)
  POST requests require a Content-length header.
```

This is documented GCS behaviour, not a quirk: *"For initiating a multipart upload, this value is
0. Required: Yes."*

**2. A malformed namespace on the complete document.** `MultipartOutputStream` declares
`xmlns="http:s3.amazonaws.com/doc/2006-03-01/"` — note the missing `//`, which is not a valid URI.
Amazon ignores it, GCS schema-validates it:

```
ServiceException: statusCode=400: MalformedCompleteMultipartUploadRequest
  element "CompleteMultipartUpload" not allowed anywhere;
  expected … xmlns:ns="http://s3.amazonaws.com/doc/2006-03-01/"
```

Both are present in the library's `master` as well, so upgrading does not help, and neither is
behind a configuration flag — no setting can work around this.

Note that GCS does **not** require a namespace — it rejects only a malformed one. The
un-namespaced form `<CompleteMultipartUpload>` is accepted by both GCS and MinIO (verified), and is
what the sample request in the Amazon S3 API reference itself uses.

Neither is behind a configuration flag, and `MULTIPART_THRESHOLD` is a `private static final` with
no `Config` property — **no setting works around this**, and upgrading does not help: 0.1.25 and
`master` carry byte-identical constants.

GCS multipart itself was never the problem: the same 6 MB upload through the AWS CLI succeeds
against the same bucket. The defects are in the client library only.

**Resolution.** `putLargeFile` drives initiate / upload-part / complete directly through the
library's public low-level `Request` API: `requestBody(new byte[0])` on initiate — empty, not
absent, which is what emits `Content-Length: 0` — and the complete document built with the
library's own public `Xml` builder (`com.github.davidmoten.aws.lw.client.xml.builder.Xml`), with no
namespace at all. GCS does **not** require a namespace, it rejects only a malformed one; the
un-namespaced form matches the sample request in the Amazon S3 API reference and is accepted by GCS
and MinIO alike (both verified). Net change: 47 insertions / 11 deletions in one method plus a
`readFully` helper. `putSmallFile` and everything else are untouched.

A one-line patch would **not** have been sufficient: adding `.requestBody(new byte[0])` through the
helper's `transformCreateRequest` hook clears the 411, and the upload then fails at the complete
step with the 400, because the helper builds that document internally with no hook. Owning the
complete step means owning the whole flow.

**How it is guarded:**

| test | backend | asserts |
|---|---|---|
| `S3FileStoreServiceMultipartWireTest.fixedInitiateSendsEmptyBodyNotAbsentBody` | none (fake transport) | initiate body is empty, not absent → `Content-Length: 0` |
| `S3FileStoreServiceMultipartWireTest.fixedCompleteDocumentIsWellFormedOrderedAndCarriesNoMalformedNamespace` | none (fake transport) | no malformed namespace, parts ascending, ETags verbatim |
| `GcsS3FileStoreServiceTest.putAndGetLargeFileViaMultipart` (+ 3-part variant) | real GCS bucket | 6 MB and 3-part round trips, byte-exact, metadata survives |
| `S3FileStoreServiceTest.testPutAndGetLargeFileMultipart` | MinIO | pre-existing test, unchanged — no regression on Amazon-compatible backends |

Run against the **original** library-based code, the wire test fails exactly the two `fixed*` tests
by name and passes the other seven — i.e. it targets these two defects and nothing else.

### 6.2 `size` metadata is absent on multipart uploads

`putSmallFile()` writes `x-amz-meta-size`; `putLargeFile()` does not. `getSize()` falls back to the
object's `Content-Length` (the synthetic `__content-length__` entry), so it still returns the right
value. Relevant only once 6.1 is fixed (on MinIO/AWS it applies today).

### 6.2b An interrupted multipart upload is never aborted

`putLargeFile()` does not send `AbortMultipartUpload` when a part fails, so an interrupted upload
leaves an incomplete multipart upload on the bucket — invisible as an object, but **billed as
storage**. Set an `AbortIncompleteMultipartUpload` lifecycle rule (age 7 days) on any bucket used
with this backend. Relevant once 6.1 is fixed; on MinIO/AWS it applies today.

### 6.3 The bucket cannot be created by the client

Bucket creation over the XML API requires an `x-goog-project-id` header that
`aws-lightweight-client-java` does not send. `MinioFixture`'s `PUT`-the-bucket trick therefore
cannot work on GCS; `GcsFixture` only verifies reachability and fails with a pointer to this file.

### 6.4 AWS CLI v2 ≥ 2.23 fails against GCS

Manual probing with the AWS CLI returns `SignatureDoesNotMatch` because the CLI injects CRC32
checksums and `aws-chunked` payload signing. Disable it:

```bash
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
```

This affects the CLI only — the Java client performs plain SigV4 and is unaffected.

### 6.5 `getAccessUrl()` outside OSGi

`getAccessUrl()` returns `gcsstore:<fileId>-<name>`, which `new URL(...)` can only parse when a
handler for that protocol is registered. Inside Karaf the component registers one in `activate()`.
The test installs an equivalent JDK handler itself; note that
`FileStoreUrlStreamHandler` extends `AbstractURLStreamHandlerService` and must be wrapped rather
than used directly, since its `parseURL` needs an OSGi-supplied `realHandler`.

### 6.6 The access URL never expires

`getAccessUrl()` is **not** a presigned URL — it carries no signature and no expiry, and is only
resolvable inside the OSGi runtime. For time-limited public links use `DownloadServlet` with a
JWT whose `expirationTime` is greater than 0. This is the same for all three backends; it is not a
GCS limitation.

### 6.7 Presigned (live-access) URLs: GCS supports them, the library does not

GCS accepts S3-style presigned GET URLs made with the HMAC key — verified with the AWS CLI
(`aws s3 presign --endpoint-url https://storage.googleapis.com`): plain `curl` without
credentials → `200`, tampered signature → `403`, expired → `400`. So the *capability* is identical
to MinIO/S3.

However `Request.presignedUrl(ttl, unit)` of `aws-lightweight-client-java` is unusable — on GCS
**and** on MinIO. It signs an empty `x-amz-content-sha256` header
(`X-Amz-SignedHeaders=host;x-amz-content-sha256`) that no browser ever sends, so the signature can
never verify:

```
GCS:   400 MalformedSecurityHeader — "Header was included in signedheaders, but not in the
       request. ParameterName: x-amz-content-sha256"
MinIO: 400 InvalidRequest — "The authorization mechanism you have provided is not supported"
```

A canonical presigned GET signs `host` only (that is what the AWS CLI emits). This is the third
library bug found in this work (see section 7); nothing in `filestore-s3` currently uses presigned
URLs, so it was **not** fixed — adding live-access URLs would be a `FileStoreService` API change
and a security-model change (downloads bypass `DownloadServlet` and its JWT check), i.e. a
separate design decision.

---

## 7. Status

GCS is supported for files of any size. One production method changed (`putLargeFile`, §6.1);
everything else here is test and documentation.

| file | what it is |
|---|---|
| `src/main/.../S3FileStoreService.java` | **the only production change** — `putLargeFile`, 47 insertions / 11 deletions |
| `src/test/.../fixture/DotEnv.java` | zero-dependency `.env` loader |
| `src/test/.../fixture/GcsFixture.java` | opt-in GCS config + per-object cleanup |
| `src/test/.../GcsS3FileStoreServiceTest.java` | integration test against a real bucket (12 tests) |
| `src/test/.../S3FileStoreServiceMultipartWireTest.java` | network-free guard on the two library defects (9 tests) |
| `.env.example`, `GCS_INTEROP.md`, `CONFIGURATION.md`, `SECURITY.md`, `GCS_ASSESSMENT.md` | documentation |

**Verified:** 31 tests, 0 skipped, 0 failures — `GcsS3FileStoreServiceTest` 12 (real bucket),
`S3FileStoreServiceTest` 10 (MinIO, unchanged), `S3FileStoreServiceMultipartWireTest` 9.

**Open follow-ups:**

- Report the three library defects upstream: initiate body, `xmlns` typo, and the presigned
  `x-amz-content-sha256` (§6.7).
- `CompleteMultipartUpload` on **Amazon S3 proper** may return `200 OK` with an embedded `<Error>`
  body, which `.execute()` does not inspect. Unchanged from the original library behaviour; GCS
  returns a real `400` and MinIO is not documented to do this.
- Security hardening before production — see [SECURITY.md](SECURITY.md).
