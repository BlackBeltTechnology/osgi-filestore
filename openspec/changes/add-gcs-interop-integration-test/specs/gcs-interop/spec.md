# Spec: gcs-interop

S3-interoperability of `S3FileStoreService` against Google Cloud Storage, verified by an opt-in integration test driven from an `.env` file.

## ADDED Requirements

### Requirement: Opt-in execution

The integration test MUST NOT run unless explicitly enabled, so that the default build requires no cloud credentials.

#### Scenario: No configuration present
- **GIVEN** no `filestore-s3/.env` file exists and `GCS_TEST_ENABLED` is unset
- **WHEN** `mvn test -pl filestore-s3` runs
- **THEN** every `GcsS3FileStoreServiceTest` test is reported as skipped
- **AND** the existing MinIO suite runs and passes unchanged

#### Scenario: Configuration present but disabled
- **GIVEN** `filestore-s3/.env` exists with `GCS_TEST_ENABLED=false`
- **WHEN** the test class is executed
- **THEN** every test is skipped

### Requirement: Client construction from configuration

`activate()` MUST build a working S3 client from `accessKey`, `secretKey`, `region` and `endpoint` alone, with no pre-injected client.

#### Scenario: Activate against a custom endpoint
- **GIVEN** a `Config` supplying `endpoint = "https://storage.googleapis.com"` and valid HMAC credentials
- **AND** `target.s3Client` has NOT been assigned by the test
- **WHEN** `activate(null, config)` is called
- **THEN** `s3Client` is non-null
- **AND** `getProtocol()` returns the configured protocol, lower-cased

#### Scenario: Endpoint without trailing slash
- **GIVEN** `endpoint` is supplied without a trailing `/`
- **WHEN** `activate()` runs
- **THEN** requests still resolve, i.e. the trailing slash is appended internally

### Requirement: Custom metadata round-trip over the S3 interop API

Google Cloud Storage MUST return custom object metadata under the `x-amz-meta-` prefix so that `Response.metadata()` can read it. This requirement pins undocumented third-party behaviour.

#### Scenario: Metadata written by put() is readable
- **GIVEN** `put(stream, "test.txt", "text/plain")` stored a file and returned `fileId`
- **WHEN** `getFileName(fileId)`, `getMimeType(fileId)`, `getSize(fileId)` and `getCreateTime(fileId)` are called
- **THEN** `getFileName` returns `"test.txt"`
- **AND** `getMimeType` returns `"text/plain"`
- **AND** `getSize` returns the exact byte count of the stored content
- **AND** `getCreateTime` returns a non-null `Date`
- **AND** none of the calls throws `NullPointerException` or `NumberFormatException`

### Requirement: Small-file upload path

Files below `MULTIPART_THRESHOLD` MUST round-trip byte-exactly via the single-part PUT path.

#### Scenario: Store and retrieve a small file
- **GIVEN** an `InputStream` over the bytes of `"test"`
- **WHEN** `put(...)` then `get(fileId)` are called
- **THEN** the retrieved bytes equal the original bytes
- **AND** `exists(fileId)` returns `true`
- **AND** the returned `fileId` is a 32-character identifier containing no `-`

### Requirement: Large-file multipart upload works on Google Cloud Storage

Files at or above `MULTIPART_THRESHOLD` (5 MB) MUST round-trip on Google Cloud Storage. The `Multipart` helper of `aws-lightweight-client-java` MUST NOT be used, because it deviates from the S3 specification in two ways that Amazon S3 and MinIO tolerate and GCS rejects:

1. it sends the initiate request (`POST ?uploads`) with an absent rather than empty body, so no `Content-Length` header is emitted — GCS answers HTTP 411 `Length Required`;
2. it declares `xmlns="http:s3.amazonaws.com/doc/2006-03-01/"` (a malformed URI) on the `CompleteMultipartUpload` document — GCS answers HTTP 400 `MalformedCompleteMultipartUploadRequest`.

Neither is reachable through configuration, and `MULTIPART_THRESHOLD` is a `private static final` with no `Config` property, so no setting works around this. `putLargeFile` MUST therefore drive initiate / upload-part / complete directly: an empty body on initiate, and a complete document carrying no malformed namespace. GCS accepts both the un-namespaced form and a correctly declared one, and rejects only a malformed one.

#### Scenario: A 6 MB upload round-trips on a real GCS bucket
- **GIVEN** an `InputStream` of 6 MB, i.e. above `MULTIPART_THRESHOLD`
- **WHEN** `put(...)` is called against a GCS bucket
- **THEN** `exists(fileId)` returns `true`
- **AND** `get(fileId)` returns the 6 MB byte-exactly
- **AND** `getSize(fileId)` returns 6 MB, resolved through the `Content-Length` fallback because the multipart path writes no `size` metadata entry
- **AND** `getFileName` and `getMimeType` survive assembly of the parts

#### Scenario: A three-part upload preserves part order
- **GIVEN** a payload of `2 × MULTIPART_THRESHOLD + 1` bytes with non-uniform content
- **WHEN** it is uploaded and retrieved
- **THEN** the bytes match exactly, so reordering or truncation of parts would be detected

#### Scenario: The initiate request is pinned without a network
- **GIVEN** the client library's `HttpClient` seam replaced by a recording fake
- **WHEN** a payload above the threshold is uploaded
- **THEN** the recorded initiate request has an empty (non-`null`) body, which is what causes `Content-Length: 0` to be emitted
- **AND** run against the original library-based implementation this test fails, i.e. it guards the defect rather than restating the implementation

#### Scenario: The complete document is pinned without a network
- **GIVEN** the same recording fake
- **WHEN** a payload spanning three parts is uploaded
- **THEN** the recorded `CompleteMultipartUpload` document declares no malformed namespace
- **AND** the parts are numbered from 1 in ascending order with their ETags echoed verbatim

#### Scenario: Amazon-compatible backends are unaffected
- **GIVEN** a MinIO backend, which tolerates both deviations
- **WHEN** the existing `testPutAndGetLargeFileMultipart` 6 MB test runs
- **THEN** it passes unchanged, i.e. the replacement is a superset of the previous behaviour

### Requirement: Existence check

`exists()` MUST report absence as a boolean rather than propagating the underlying HTTP 404 as an exception.

#### Scenario: Missing object
- **GIVEN** a `fileId` that was never stored
- **WHEN** `exists(fileId)` is called
- **THEN** it returns `false` without throwing

### Requirement: Access URL and protocol-prefixed identifiers

`getAccessUrl()` MUST return a URL of the form `<protocol>:<fileId>-<fileName>`, and every accessor MUST accept identifiers that already carry the `<protocol>:` prefix.

#### Scenario: Access URL format
- **GIVEN** a stored file with `fileId` and file name `test.txt`
- **AND** a `URLStreamHandler` for the configured protocol is registered
- **WHEN** `getAccessUrl(fileId)` is called
- **THEN** the URL string equals `"<protocol>:<fileId>-test.txt"`

#### Scenario: Identifier carrying the protocol prefix
- **GIVEN** a stored file with `fileId`
- **WHEN** `exists("<protocol>:" + fileId)` is called
- **THEN** it returns `true`, i.e. `getStrippedId` removes the prefix

### Requirement: File name sanitisation on the stored metadata

The file name persisted as S3 user metadata MUST be the result of `FilenameUtils.makeValidFilename`, not the raw caller-supplied name.

#### Scenario: File name containing special characters
- **GIVEN** the caller supplies `fileName = "my$file (copy)[1].txt"`
- **WHEN** `put(...)` then `getFileName(fileId)` are called
- **THEN** the stored name equals `"myfile copy1.txt"`, i.e. `$()[]` removed and single spaces preserved

### Requirement: MIME type resolution when not supplied

When the caller passes a null or empty mime type, the component MUST resolve it through the injected `MimeTypeService` before storing it as metadata.

#### Scenario: Null mime type falls back to MimeTypeService
- **GIVEN** `put(stream, "test.txt", null)` is called
- **AND** `MimeTypeService.getMimeType("test.txt")` resolves to `"text/plain"`
- **WHEN** `getMimeType(fileId)` is called
- **THEN** it returns `"text/plain"`

### Requirement: Test isolation

The test MUST NOT leave residue in the bucket and MUST NOT touch objects it did not create.

#### Scenario: Cleanup after each test
- **GIVEN** a test stored one or more files
- **WHEN** the test method finishes, whether it passed or failed
- **THEN** exactly those object keys are deleted
- **AND** no other object in the bucket is read, modified or deleted
