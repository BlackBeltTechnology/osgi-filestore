# Design: fix-s3-filestore-issues

## File Modified

`filestore-s3/src/main/java/hu/blackbelt/osgi/filestore/s3/S3FileStoreService.java`

All changes are confined to this single file. No new classes, no dependency changes.

## Architecture

```
put(InputStream data, ...)
  │
  ├─ resolve filename & mimeType (unchanged)
  │
  ├─ readUpTo(data, MULTIPART_THRESHOLD)
  │     │
  │     ├─ totalRead < MULTIPART_THRESHOLD ──▶ smallFilePut(buffer, fileId, metadata)
  │     │                                        └─ existing single-part PUT path
  │     │                                           with .requestBody(trimmedBuffer)
  │     │
  │     └─ totalRead == MULTIPART_THRESHOLD ──▶ check if stream has more
  │           │
  │           ├─ EOF (exactly 5MB) ──▶ smallFilePut(buffer, ...)
  │           │
  │           └─ more data ──▶ largeFilePut(buffer, data, fileId, metadata)
  │                              └─ SequenceInputStream(buffer, remaining)
  │                              └─ Multipart.s3(s3Client)
  │                                   .bucket(bucketName).key(fileId)
  │                                   .transformCreateRequest(r -> r
  │                                       .metadata(...).header(...))
  │                                   .upload(() -> combinedStream)
  │
  └─ return fileId
```

## Design Decisions

### Decision: Buffer probing for threshold detection

We allocate a `byte[MULTIPART_THRESHOLD]` buffer and read in a loop until either the buffer is full or EOF is reached. To detect "stream has more data" when the buffer is exactly full, we do one additional `data.read()` call:

- Returns `-1` → stream is exactly 5MB, treat as small file
- Returns `>= 0` → stream exceeds threshold, that extra byte joins the combined stream

The extra byte is prepended to the `SequenceInputStream`:
```
SequenceInputStream(
    new ByteArrayInputStream(buffer),           // the 5MB buffer
    new ByteArrayInputStream(new byte[]{extraByte}),  // the probe byte
    originalStream                              // remaining data
)
```

Since `SequenceInputStream` only takes two streams, we nest:
```java
new SequenceInputStream(
    new ByteArrayInputStream(fullBuffer),
    new SequenceInputStream(
        new ByteArrayInputStream(new byte[]{(byte) extraByte}),
        data
    )
)
```

### Decision: Extract helper methods, don't restructure

Factor out two private methods from `put()`:
- `putSmallFile(String fileId, byte[] content, String fileName, String mimeType, String createTime)` — the current single-part PUT logic
- `putLargeFile(String fileId, InputStream combinedStream, String fileName, String mimeType, String createTime)` — new multipart path

This keeps the `put()` method readable without changing the class structure.

### Decision: New imports needed

```java
import com.github.davidmoten.aws.lw.client.Multipart;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
```

### Decision: getObjectMetadata() returns both metadata and content-length

Currently `getObjectMetadata()` returns `Map<String, String>` containing only user metadata. To support the `getSize()` fallback, we modify it to also include the `Content-Length` header in the returned map under a special key `__content-length__` (not a valid S3 metadata key, so no collision).

```
Response response = s3Client.path(bucketName, fileId)
        .method(HttpMethod.HEAD)
        .response();
// ... existing metadata extraction ...
response.firstHeader("Content-Length")
        .ifPresent(cl -> result.put("__content-length__", cl));
return result;
```

`getSize()` then:
1. Tries `metadata.get(META_SIZE)` → parse as long if present
2. Falls back to `metadata.get("__content-length__")` → parse as long if present
3. Throws `IllegalArgumentException` if neither available

### Decision: getStrippedId() null guard

Simple early return:
```java
if (id == null) {
    throw new IllegalArgumentException(FILE_ID_CANNOT_BE_NULL);
}
```

Added as the first line. Existing logic follows unchanged.

### Decision: MULTIPART_THRESHOLD as a constant

```java
private static final int MULTIPART_THRESHOLD = 5 * 1024 * 1024;
```

Not configurable via OSGi config — 5MB is the S3 multipart minimum part size, making it a natural fixed boundary.

## What Does NOT Change

- `Client` construction in `activate()` — no `forcePathStyle`, no `close()` needed
- `deactivate()` — `s3Client = null` is sufficient (no connection pool)
- `exists()`, `get()`, `getMimeType()`, `getFileName()`, `getCreateTime()`, `getAccessUrl()` — unchanged (except they now benefit from the null guard in `getStrippedId()`)
- Exception handling pattern — still catches `ServiceException`
- File ID generation — still UUID v4 without dashes
- OSGi component annotations — unchanged
