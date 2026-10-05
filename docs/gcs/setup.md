# Setting up `filestore-s3` against Google Cloud Storage

GCS exposes an S3-compatible XML API. `S3FileStoreService` works against it for files of any size:
GCS accepts `x-amz-*` request headers and returns custom metadata under the `x-amz-meta-` prefix
that the client library expects. No GCS-specific code or configuration property exists; GCS is
ordinary S3 configuration pointed at `https://storage.googleapis.com` with HMAC credentials.

This page covers provisioning, configuration, running the tests and GCS quirks. Property reference
and silent-failure traps: [CONFIGURATION.md](../../filestore-s3/CONFIGURATION.md). Security
posture: [SECURITY.md](../../filestore-s3/SECURITY.md).

---

## 1. What you need

| | |
|---|---|
| A GCS bucket | created up front; neither the test nor the runtime can create it (§7.1) |
| A service account | with `roles/storage.objectAdmin` **on that bucket only** |
| An HMAC key for that service account | the access ID / secret become `accessKey` / `secretKey` |

> The HMAC key is **not** a service-account JSON key. It is created under
> *Cloud Storage → Settings → Interoperability*.

---

## 2. Provisioning with the `gcloud` CLI

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

# 3. TEST BUCKETS ONLY: delete objects after 1 day (never on production, see §6)
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

## 3. Provisioning in the Cloud Console

Keep the project selector pinned to your project on every page.

1. **Billing**: `console.cloud.google.com/billing` → *Link a billing account*.
   A project without billing cannot create a bucket.
2. **Enable the API**: ☰ *APIs & Services → Library* → search **Cloud Storage API** → **Enable**.
3. **Create the bucket**: ☰ *Cloud Storage → Buckets* → **Create**
   - *Get started*: globally unique name
   - *Choose where to store your data*: **Region**, e.g. `europe-west3`
   - *Choose a storage class*: **Standard**
   - *Choose how to control access*: Access control = **Uniform**
   - **Create**
   - Test buckets only: open the bucket → *Lifecycle* → **Add a rule** → *Delete object* / *Age = 1 day*
4. **Create the service account**: ☰ *IAM & Admin → Service Accounts* → **Create service account**
   - Name `filestore-s3-test`
   - **Skip** the "Grant this service account access to project" step
   - **Done**, and do **not** create a JSON key here
5. **Grant bucket access**: ☰ *Cloud Storage → Buckets* → your bucket → **Permissions** tab →
   **Grant access**
   - *New principals*: the service-account e-mail
   - *Role*: **Cloud Storage → Storage Object Admin**
   - **Save**
6. **Create the HMAC key**: ☰ *Cloud Storage → **Settings*** → **Interoperability** tab
   - Click **Enable interoperability access** if the button is shown
   - Under *Access keys for service accounts* → **Create a key for a service account**
   - Pick the service account → **Create key**
   - Copy **Access key** (`GOOG1E…`) and **Secret**; the secret is displayed only once
   - If the button is greyed out, give yourself *IAM → Storage HMAC Key Admin*

---

## 4. Configuration

### judo-platform (environment variables)

```bash
export JUDO_PLATFORM_FILESTORE=s3                              # exact, lower-case (Trap 1)
export JUDO_PLATFORM_S3_BUCKET=acme-prod-files
export JUDO_PLATFORM_S3_ENDPOINT=https://storage.googleapis.com
export JUDO_PLATFORM_S3_ACCESS_KEY="$(read-from-secret-manager gcs-hmac-id)"
export JUDO_PLATFORM_S3_SECRET_KEY="$(read-from-secret-manager gcs-hmac-secret)"
export JUDO_PLATFORM_FILESTORE_TOKEN_EXPIRY=15                 # minutes; 0 = never expires
# plus an explicit CORS origin list on both servlet PIDs (SECURITY.md S-8)
```

### Standalone Karaf (`.cfg` file)

`etc/hu.blackbelt.osgi.filestore.s3.S3FileStoreService.cfg`:

```properties
protocol   = gcsstore
bucketName = <your bucket>
endpoint   = https://storage.googleapis.com
accessKey  = GOOG1E...
secretKey  = <the secret>
region     = us-east-1
```

Then `feature:install filestore-s3`. `region` is cosmetic on GCS: it is signed but not matched
against the bucket's real location. Every property and both paths are described in
[CONFIGURATION.md](../../filestore-s3/CONFIGURATION.md) §1–3.

### Tests (`filestore-s3/.env`)

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

Only the tests read this file; the runtime never does. `**/.env` is git-ignored: **never commit
it.** Lookup order is real environment variable → `filestore-s3/.env` → default, so CI can inject
the same keys as secrets. Use a different file with `FILESTORE_ENV_FILE=/path/to/file`.

---

## 5. Running the tests

```bash
# with .env present -> runs against the real bucket
mvn test -pl filestore-s3 -am -Dtest=GcsS3FileStoreServiceTest -Dsurefire.failIfNoSpecifiedTests=false

# without .env -> every GCS test is skipped, nothing else changes
FILESTORE_ENV_FILE=/nonexistent.env mvn test -pl filestore-s3 -am
```

When not configured, all `GcsS3FileStoreServiceTest` tests are skipped and the build is
unaffected. `S3FileStoreServiceMultipartWireTest` needs no network and always runs.

---

## 6. Production bucket

Once per environment:

```bash
gcloud storage buckets update gs://acme-prod-files --uniform-bucket-level-access
# abort incomplete multipart uploads after 7 days. NOT a delete rule.
cat > /tmp/lifecycle.json <<'EOF'
{"lifecycle":{"rule":[{"action":{"type":"AbortIncompleteMultipartUpload"},"condition":{"age":7}}]}}
EOF
gcloud storage buckets update gs://acme-prod-files --lifecycle-file=/tmp/lifecycle.json
```

Do **not** copy the test bucket's `Delete / age 1 day` rule: it deletes live files. Other
bucket-side controls (public-access prevention, versioning, audit logs, CMEK) are in
[SECURITY.md §3](../../filestore-s3/SECURITY.md#3-bucket-side-configuration-not-controlled-by-this-code).

Smoke check after deploy. It catches the silent-configuration traps in
[CONFIGURATION.md §6](../../filestore-s3/CONFIGURATION.md#6-silent-failure-traps):

```bash
curl -F "file=@hello.txt" -H "X-Token: $TOKEN" https://host/app/upload   # -> fileId
curl -H "X-Token: $TOKEN" https://host/app/download?id=<fileId>          # -> bytes
```

---

## 7. GCS quirks

Files ≥ 5 MB needed a code fix; that is described in [multipart-fix.md](multipart-fix.md).

### 7.1 The bucket cannot be created by the client

Bucket creation over the XML API requires an `x-goog-project-id` header that
`aws-lightweight-client-java` does not send. `MinioFixture`'s `PUT`-the-bucket trick therefore
cannot work on GCS; `GcsFixture` only verifies reachability and fails with a pointer to this file.

### 7.2 AWS CLI v2 ≥ 2.23 fails against GCS

Manual probing with the AWS CLI returns `SignatureDoesNotMatch` because the CLI injects CRC32
checksums and `aws-chunked` payload signing. Disable it:

```bash
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
```

This affects the CLI only. The Java client performs plain SigV4 and is unaffected.

### 7.3 `getAccessUrl()` outside OSGi

`getAccessUrl()` returns `gcsstore:<fileId>-<name>`, which `new URL(...)` can only parse when a
handler for that protocol is registered. Inside Karaf the component registers one in `activate()`.
The test installs an equivalent JDK handler itself. `FileStoreUrlStreamHandler` extends
`AbstractURLStreamHandlerService` and must be wrapped rather than used directly, since its
`parseURL` needs an OSGi-supplied `realHandler`.

### 7.4 The access URL is not a public link

`getAccessUrl()` is **not** a presigned URL. It carries no signature and no expiry, and is only
resolvable inside the OSGi runtime. For time-limited links use `DownloadServlet` with a JWT whose
`expirationTime` is greater than 0. This is the same for all backends (SECURITY.md S-7).

### 7.5 Presigned URLs: GCS supports them, the library does not

GCS accepts S3-style presigned GET URLs made with the HMAC key. Verified with the AWS CLI
(`aws s3 presign --endpoint-url https://storage.googleapis.com`): plain `curl` without credentials
→ `200`, tampered signature → `403`, expired → `400`.

`Request.presignedUrl(ttl, unit)` of `aws-lightweight-client-java` is unusable, on GCS **and**
MinIO. It signs an empty `x-amz-content-sha256` header
(`X-Amz-SignedHeaders=host;x-amz-content-sha256`) that no browser sends, so the signature can never
verify:

```
GCS:   400 MalformedSecurityHeader — "Header was included in signedheaders, but not in the
       request. ParameterName: x-amz-content-sha256"
MinIO: 400 InvalidRequest — "The authorization mechanism you have provided is not supported"
```

A canonical presigned GET signs `host` only. Nothing in `filestore-s3` uses presigned URLs, so this
was **not** fixed. Adding them would change the `FileStoreService` API and the security model
(downloads would bypass `DownloadServlet` and its JWT check), so it is a separate design decision.
