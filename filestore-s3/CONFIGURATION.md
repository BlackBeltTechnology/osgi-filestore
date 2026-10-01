# Configuration — `filestore-s3` (AWS S3 / MinIO / Google Cloud Storage)

Every way this backend can be configured, what the defaults actually are, and the ways it can fail
**silently**. Security implications of these settings are in [SECURITY.md](SECURITY.md).

There are **two independent configuration paths**, and they use different variable names. Most
confusion about this module comes from mixing them up:

| path | who drives it | how you set it |
|---|---|---|
| **A — judo-platform dispatcher** (the normal one) | `DispatcherServiceActivator` in judo-services | `JUDO_PLATFORM_*` environment variables |
| **B — direct OSGi config** | Config Admin | a `.cfg` file dropped into `<karaf>/etc/` |

`filestore-s3` ships **no config-templates of its own** (unlike `filestore-filesystem` and
`filestore-rdbms`), so outside path A there is no env-var plumbing — path B is the only alternative.

---

## 1. The component's own properties

`S3FileStoreService.Config` (`S3FileStoreService.java:38-53`). These are the real names, as seen by
Config Admin:

| property | required | default | notes |
|---|---|---|---|
| `protocol` | **yes** | — | URL scheme for the OSGi URL handler, e.g. `gcsstore`. Must be unique per backend instance |
| `bucketName` | **yes** | — | Must already exist — this code cannot create it (see §6) |
| `accessKey` | **yes** | — | GCS: HMAC access ID (`GOOG1E…`) |
| `secretKey` | **yes** | — | GCS: HMAC secret. Plaintext in Config Admin — see SECURITY.md S-2 |
| `endpoint` | no | `""` | Empty = real AWS S3. GCS: `https://storage.googleapis.com`. A trailing `/` is added if missing |
| `region` | no | `us-east-1` | Cosmetic on GCS: signed but not matched against the bucket's real location |

`@Component(configurationPolicy = REQUIRE)` — **with no configuration the component never
activates, and says nothing about it.** No `FileStoreService` is registered; anything depending on
it simply stays unsatisfied. This is trap #1 (§6).

---

## 2. Path A — judo-platform environment variables

The normal deployment route. `osgi-configuration-mapper` strips the `JUDO_PLATFORM_` prefix and
converts `UPPER_SNAKE_CASE` → `lowerCamelCase`, then `DispatcherServiceActivator` maps those onto
the component properties above.

```bash
JUDO_PLATFORM_FILESTORE=s3                                  # selects the S3 backend
JUDO_PLATFORM_S3_BUCKET=my-app-files                        # -> bucketName
JUDO_PLATFORM_S3_ENDPOINT=https://storage.googleapis.com    # -> endpoint   (GCS)
JUDO_PLATFORM_S3_ACCESS_KEY=GOOG1E...                       # -> accessKey
JUDO_PLATFORM_S3_SECRET_KEY=...                             # -> secretKey
JUDO_PLATFORM_S3_REGION=us-east-1                           # -> region     (optional)
```

That is the complete set — **GCS needs no extra plumbing**; it is ordinary S3 configuration pointed
at Google's endpoint with HMAC credentials.

Notes:

- **`protocol` is not user-settable on this path.** The dispatcher derives it as
  `<modelName> + "store"`.
- No project ID, session token or path-style flag is needed for object operations. (A project ID is
  only needed to *create* a bucket, which this code never does.)
- Related dispatcher variables: `JUDO_PLATFORM_FILESTORE_TOKEN_EXPIRY` (see SECURITY.md S-5) and the
  `filestore.cors.*` family (S-8). `filestoreDirectory` applies to the **filesystem** backend only.

> `fileStoreType`, `fileStoreProtocol`, `fileStoreTable` — seen in `osgi-filestore`'s own
> config-templates — belong to a **different** mechanism that only covers filesystem/rdbms, and
> nothing in judo-ng ever sets them. Ignore them when configuring S3.

---

## 3. Path B — direct `.cfg` file

For a standalone Karaf, or any runtime without the dispatcher. Drop into `<karaf>/etc/`:

**`hu.blackbelt.osgi.filestore.s3.S3FileStoreService.cfg`**

```properties
protocol=gcsstore
bucketName=my-app-files
endpoint=https://storage.googleapis.com
accessKey=GOOG1E...
secretKey=...
region=us-east-1
```

```
karaf@root()> feature:install filestore-s3
```

File permissions matter — this contains a live credential (`chmod 0600`).

---

## 4. Servlet configuration (upload / download endpoints)

Separate PIDs, owned by `filestore-servlet`. Defaults as declared in the source:

| property | default | notes |
|---|---|---|
| `servletPath` | — | required |
| `maxSize` | `52428800` | **bytes**, despite the `_KB` constant name and "(kB)" label — compared directly against `Content-Length` (`UploadServlet.java:187`). = 50 MB |
| `maxFileSize` | `52428800` | same units caveat |
| `tokenRequired` | **`false`** | off by default, both servlets — SECURITY.md S-4 |
| `cors.allowOrigin` | **`*`** | SECURITY.md S-8 |
| `cors.allowCredentials` | **`true`** | unsafe combined with `*` |
| `slowUploads` | `0` ms | artificial delay, for testing progress UIs |
| `noDataTimeout` | `20000` ms | idle-connection timeout |

Token expiry lives in yet another PID (`filestore-security`): `expirationTime`, default **`0` =
never expires**.

**Size reconciliation:** `maxSize` (50 MB) and the backend's 5 MB multipart threshold are
independent. Since the multipart fix, the S3 backend stores what the servlet accepts on GCS; before
it, uploads between 5 and 50 MB were accepted and then failed. See `GCS_INTEROP.md` §6.1.

---

## 5. Test configuration (`.env`)

Only for the opt-in integration test; never read by the runtime. `DotEnv` looks up, in order:
real environment variable → `filestore-s3/.env` (override the path with `FILESTORE_ENV_FILE`) →
default. A missing file is not an error; the tests simply skip.

```properties
GCS_TEST_ENABLED=true
GCS_BUCKET_NAME=my-filestore-test-bucket
GCS_ENDPOINT=https://storage.googleapis.com
GCS_ACCESS_KEY=GOOG1E...
GCS_SECRET_KEY=...
GCS_REGION=us-east-1
GCS_PROTOCOL=gcsstore
```

`**/.env` is git-ignored. See `.env.example` and `GCS_INTEROP.md` for provisioning.

---

## 6. Silent-failure traps

Each of these produces **no error message**. They are the reason a deployment smoke check is worth
more than any amount of config review.

### Trap 1 — a typo in `JUDO_PLATFORM_FILESTORE` disables the endpoints

`DispatcherServiceActivator.getPIDs()` matches the value **exactly and case-sensitively** against
`"filesystem"`, `"rdbms"`, `"s3"`. Anything else — `S3`, `gcs`, a trailing space — falls into an
`else` branch that removes the filestore PID **and both the `FILESTORE_UPLOAD` and
`FILESTORE_DOWNLOAD` servlet PIDs**. Upload and download simply cease to exist. No warning.

### Trap 2 — missing configuration means the component never starts

`ConfigurationPolicy.REQUIRE`: no config → no activation → no `FileStoreService` registered. On path
B (no `.cfg`, or a misnamed one) this is easy to hit. Check with:

```
karaf@root()> scr:list | grep -i s3
karaf@root()> service:list hu.blackbelt.osgi.filestore.api.FileStoreService
```

### Trap 3 — the servlet feature has no S3 conditional

`features/src/main/feature/feature.xml` bundles `filestore-servlet` conditionally on
`filestore-filesystem` or `filestore-rdbms` — **there is no `filestore-s3` condition**. Installing
`filestore-s3` + `filestore-servlet` *without* filesystem/rdbms yields the servlet feature with no
servlet bundle behind it. Currently masked because `filestore-full` also pulls in
`filestore-filesystem`.

### Trap 4 — `protocol` collisions

Two backends registering the same `protocol` both try to register a URL handler for that scheme.
Keep protocols distinct per instance.

### Trap 5 — wrong `endpoint` scheme

`http://` is accepted silently and sends file bytes in cleartext (SECURITY.md S-3).

### Trap 6 — the bucket must pre-exist

Bucket creation over the XML API needs an `x-goog-project-id` header the client never sends, so
`MinioFixture`'s create-on-demand trick cannot work on GCS. Provision the bucket first
(`GCS_INTEROP.md` §2/§3).

---

## 7. Worked example — GCS in judo-platform

```bash
# backend
export JUDO_PLATFORM_FILESTORE=s3
export JUDO_PLATFORM_S3_BUCKET=acme-prod-files
export JUDO_PLATFORM_S3_ENDPOINT=https://storage.googleapis.com
export JUDO_PLATFORM_S3_ACCESS_KEY="$(read-from-secret-manager gcs-hmac-id)"
export JUDO_PLATFORM_S3_SECRET_KEY="$(read-from-secret-manager gcs-hmac-secret)"

# security - none of these are safe by default
export JUDO_PLATFORM_FILESTORE_TOKEN_EXPIRY=15          # minutes; 0 = never expires
# set tokenRequired=true and an explicit CORS origin list on both servlet PIDs
```

Bucket side, once per environment:

```bash
gcloud storage buckets update gs://acme-prod-files --uniform-bucket-level-access
# lifecycle: abort incomplete multipart uploads after 7 days. NOT a delete rule.
cat > /tmp/lifecycle.json <<'EOF'
{"lifecycle":{"rule":[{"action":{"type":"AbortIncompleteMultipartUpload"},"condition":{"age":7}}]}}
EOF
gcloud storage buckets update gs://acme-prod-files --lifecycle-file=/tmp/lifecycle.json
```

Verify after deploy — this is the check that catches traps 1–3:

```bash
curl -F "file=@hello.txt" -H "X-Token: $TOKEN" https://host/app/upload   # -> fileId
curl -H "X-Token: $TOKEN" https://host/app/download?id=<fileId>          # -> bytes
```

---

## References

- [SECURITY.md](SECURITY.md) — what these settings mean for security, with a hardening checklist
- [GCS_INTEROP.md](GCS_INTEROP.md) — provisioning (CLI + Console), quirks, the multipart defect
- [GCS_ASSESSMENT.md](GCS_ASSESSMENT.md) — benefits and costs of GCS as the backend
