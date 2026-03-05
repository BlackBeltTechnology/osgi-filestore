# S3FileStoreService Comparison Report

Comparison between the current working branch (`JNG-6378_AddS3Support`) and the clean version at `/home/balazs/IdeaProjects/osgi-filestore-clean`.

## S3 Client Library — the fundamental difference

| | Current (working dir) | Clean version |
|---|---|---|
| **S3 SDK** | `com.github.davidmoten.aws.lw.client` (lightweight AWS client) | `software.amazon.awssdk` (official AWS SDK v2) |
| **Client type** | `Client` | `S3Client` |

The current branch has been **migrated from the official AWS SDK v2 to a lightweight third-party AWS client library** (`aws-lightweight-client-java` by David Moten). This is the root cause of nearly every other difference.

## Detailed differences

### 1. Imports (lines 15–20)
- **Current**: imports from `com.github.davidmoten.aws.lw.client` (`Client`, `HttpMethod`, `Response`, `ResponseInputStream`, `ServiceException`)
- **Clean**: imports from `software.amazon.awssdk` (`S3Client`, `S3 model classes`, `RequestBody`, `Region`, `AwsBasicCredentials`, etc.) plus `ByteArrayInputStream`, `FileInputStream`, `URI`

### 2. Client field (line 64/68)
- **Current**: `Client s3Client`
- **Clean**: `S3Client s3Client`

### 3. `activate()` — client construction (lines 76–90)
- **Current**: Uses the lightweight client's fluent builder (`Client.s3().region().accessKey().secretKey()`). For custom endpoints, manually appends a trailing `/` and uses `.baseUrlFactory((svc, rgn) -> endpoint)`.
- **Clean**: Uses the official SDK builder (`S3Client.builder().credentialsProvider(...).region(Region.of(...)).forcePathStyle(true)`). For custom endpoints, uses `.endpointOverride(URI.create(...))`. Also sets `forcePathStyle(true)` which the current version does not.

### 4. `deactivate()` (lines 100–107)
- **Current**: Simply sets `s3Client = null` — no close/cleanup.
- **Clean**: Calls `s3Client.close()` before nulling — properly cleans up SDK resources.

### 5. `put()` — uploading (lines 132–145)
- **Current**: Reads the entire stream into `byte[]` via `data.readAllBytes()`, stores size in metadata, then uses the lightweight client's fluent API (`.path().method(PUT).header().metadata().requestBody(bytes).execute()`).
- **Clean**: Does **not** read all bytes into memory. Instead has a `getContentLength()` helper that tries to determine stream length for `ByteArrayInputStream`/`FileInputStream`, and uses `RequestBody.fromInputStream()` or `RequestBody.fromContentProvider()`. Does **not** store `META_SIZE` in user metadata. Uses `s3Client.putObject(PutObjectRequest.builder()...)`.

### 6. `getContentLength()` helper (lines 158–166 in clean)
- **Current**: Does not exist (not needed since it reads all bytes).
- **Clean**: Has a private static method to introspect the InputStream type for content length.

### 7. `exists()` (lines 149–152 vs 169–180)
- **Current**: One-liner using `s3Client.path(bucketName, fileId).exists()`.
- **Clean**: Calls `s3Client.headObject(...)` in a try/catch, returns `false` on `NoSuchKeyException`.

### 8. `get()` (lines 155–163 vs 183–193)
- **Current**: Uses `s3Client.path(...).responseInputStream()`, checks `statusCode() == 404` manually, then closes and throws.
- **Clean**: Uses `s3Client.getObject(GetObjectRequest...)`, catches `NoSuchKeyException`.

### 9. `getSize()` (lines 186–193 vs 216–231)
- **Current**: Reads `META_SIZE` from user metadata (stored during `put()`). Catches `ServiceException`.
- **Clean**: First tries `META_SIZE` from metadata, but falls back to `response.contentLength()` (the actual S3 object size). Catches `NoSuchKeyException`.

### 10. Exception types in metadata methods
- **Current**: Catches `ServiceException` (from the lightweight client).
- **Clean**: Catches `NoSuchKeyException` (from the AWS SDK).

### 11. `getObjectMetadata()` (lines 217–229 vs 255–261)
- **Current**: Manually builds an HTTP `HEAD` request, checks `response.isOk()`, and copies `response.metadata()` entries into a new `HashMap`.
- **Clean**: Uses `s3Client.headObject(HeadObjectRequest...)` and returns `response.metadata()` directly.

### 12. `getStrippedId()` (lines 235–241 vs 267–275)
- **Current**: No null check on `id`; starts with `String cleanedId = id`.
- **Clean**: Throws `IllegalArgumentException(FILE_ID_CANNOT_BE_NULL)` if `id` is null. Returns early without intermediate variable.

## Summary

The current branch replaces the **AWS SDK v2** with a **lightweight AWS client** (`aws-lightweight-client-java`). Key trade-offs:
- **Simpler dependency** (fewer transitive jars, better for OSGi), but **reads entire files into memory** (`readAllBytes()`), which is worse for large files.
- **No `s3Client.close()`** in deactivate — potential resource leak.
- **No null-check** on file ID in `getStrippedId()`.
- **No `forcePathStyle(true)`** — may break MinIO/path-style endpoint usage.
- **Always stores size in user metadata** during `put()`, so `getSize()` is simpler but slightly redundant with S3's native content-length.
