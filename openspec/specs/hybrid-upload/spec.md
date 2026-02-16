# Spec: hybrid-upload

Threshold-based upload strategy for `S3FileStoreService.put()` that uses single-part PUT for small files and multipart streaming for large files.

## Constants

- `MULTIPART_THRESHOLD = 5 * 1024 * 1024` (5MB) — files at or above this size use multipart upload

## Requirements

### Requirement: Buffer-then-decide strategy

The `put()` method must read from the input `InputStream` into a byte buffer up to `MULTIPART_THRESHOLD` bytes. After buffering:

- If the entire stream was consumed (read < MULTIPART_THRESHOLD), the file is "small" — upload via the existing single-part PUT path.
- If the buffer is full and the stream has remaining data, the file is "large" — upload via the multipart path.

The caller's InputStream must never be read twice (it may not be resettable).

#### Scenario: Small file upload (< 5MB)

- Given an InputStream containing 100 bytes
- When `put()` is called
- Then the data is read into a byte[] buffer (100 bytes)
- And uploaded via `s3Client.path(bucket, fileId).method(PUT).requestBody(buffer).metadata(...).execute()`
- And `META_SIZE` is stored in user metadata as `String.valueOf(buffer.length)`
- And the file ID is returned

#### Scenario: Exact threshold file upload (= 5MB)

- Given an InputStream containing exactly 5MB
- When `put()` is called
- Then all 5MB are read into the buffer, stream reports EOF on next read
- Then the file is treated as "small" (single-part PUT)

#### Scenario: Large file upload (> 5MB)

- Given an InputStream containing 20MB
- When `put()` is called
- Then the first 5MB are read into a buffer
- And a next read confirms the stream has more data
- Then a `SequenceInputStream` is created combining the buffered bytes and the remaining stream
- And uploaded via `Multipart.s3(s3Client).bucket(bucketName).key(fileId).transformCreateRequest(...)`.upload(() -> combinedStream)`
- And metadata (filename, mimetype, createtime) is set via `transformCreateRequest(r -> r.metadata(...).header("Content-Type", ...))`
- And `META_SIZE` is NOT stored in user metadata (total size unknown during streaming)
- And the file ID is returned

### Requirement: Metadata consistency

Both upload paths must store identical user metadata keys:

| Key             | Small file              | Large file (multipart)            |
|-----------------|-------------------------|-----------------------------------|
| `META_FILENAME` | stored via `.metadata()` | stored via `transformCreateRequest()` |
| `META_MIME_TYPE`| stored via `.metadata()` | stored via `transformCreateRequest()` |
| `META_CREATE_TIME` | stored via `.metadata()` | stored via `transformCreateRequest()` |
| `META_SIZE`     | stored (known)          | NOT stored (unknown during stream)|
| `Content-Type`  | stored via `.header()`  | stored via `transformCreateRequest()` |

### Requirement: Combined stream construction for large files

When the stream exceeds the buffer:

1. Trim the buffer to actual bytes read (if buffer was over-allocated)
2. Wrap buffer as `ByteArrayInputStream`
3. Create `SequenceInputStream(new ByteArrayInputStream(trimmedBuffer), originalStream)`
4. Pass as the `Callable<InputStream>` factory to `Multipart.upload()`

### Requirement: Memory ceiling

Peak memory per upload must not exceed approximately `MULTIPART_THRESHOLD` (5MB) regardless of total file size. The multipart API streams in 5MB part chunks internally.

## Edge Cases

- **Empty file (0 bytes)**: Treated as small file. Buffer is empty byte[]. Single-part PUT with `META_SIZE = "0"`.
- **Stream throws IOException mid-read**: Exception propagates to caller. No partial S3 object should remain (single-part PUT is atomic; multipart upload will be aborted by the library on exception).
- **Null InputStream**: Not guarded here — `FileStoreService.put()` contract assumes non-null stream.
