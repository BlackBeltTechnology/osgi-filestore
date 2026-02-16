# Spec: defensive-checks

Null-safety for file ID handling and robust size retrieval with fallback.

## Requirements

### Requirement: Null-check in getStrippedId()

`getStrippedId(String id)` must throw `IllegalArgumentException` with message `FILE_ID_CANNOT_BE_NULL` when `id` is null.

#### Scenario: Null file ID

- Given a null file ID
- When any method that calls `getStrippedId()` is invoked (`exists()`, `get()`, `getMimeType()`, `getFileName()`, `getSize()`, `getCreateTime()`, `getAccessUrl()`)
- Then `IllegalArgumentException` is thrown with message "fileId cannot be null"

#### Scenario: Valid file ID without protocol prefix

- Given file ID `"abc123def456"`
- When `getStrippedId()` is called
- Then it returns `"abc123def456"` unchanged

#### Scenario: Valid file ID with protocol prefix

- Given file ID `"s3store:abc123def456-report.pdf"`
- When `getStrippedId()` is called
- Then it returns `"abc123def456-report.pdf"` (everything after the first `:`)

### Requirement: getSize() fallback to content-length

`getSize()` must first attempt to read `META_SIZE` from S3 user metadata. If `META_SIZE` is absent or null, it must fall back to the S3 object's native content-length obtained from a HEAD request.

This handles the case where large files are uploaded via multipart (which does not store `META_SIZE`).

#### Scenario: Size from metadata (small file, META_SIZE present)

- Given a file uploaded via single-part PUT with `META_SIZE = "4096"` in user metadata
- When `getSize()` is called
- Then it returns `4096L`

#### Scenario: Size from content-length fallback (large file, no META_SIZE)

- Given a file uploaded via multipart (no `META_SIZE` in user metadata)
- And the S3 object has native content-length of `52428800` (50MB)
- When `getSize()` is called
- Then `META_SIZE` is absent in the metadata map
- And the method falls back to reading `Content-Length` from the HEAD response
- And returns `52428800L`

#### Scenario: File not found

- Given a file ID that does not exist in S3
- When `getSize()` is called
- Then `IllegalArgumentException` is thrown with message "File not found"

## Implementation Notes

- The HEAD response from the lightweight client's `response()` provides access to response headers. `Content-Length` can be read via `response.headers()` or equivalent accessor.
- The `getObjectMetadata()` helper already performs a HEAD request — the content-length header is available on the same response, so no additional S3 call is needed for the fallback.
