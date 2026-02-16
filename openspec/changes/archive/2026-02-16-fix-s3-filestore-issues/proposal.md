# Proposal: fix-s3-filestore-issues

## Why

The current S3FileStoreService (using the lightweight `aws-lightweight-client-java` library) has several issues identified by comparison with the clean AWS SDK v2 version:

1. **Memory risk**: `put()` calls `data.readAllBytes()`, loading entire files into heap. This causes OOM for large files.
2. **Missing null-check**: `getStrippedId()` doesn't guard against null file IDs, risking NPE.
3. **No size fallback**: `getSize()` only reads `META_SIZE` from user metadata. If metadata is missing, it fails instead of falling back to S3's native `contentLength`.

The lightweight client is the correct choice for this OSGi project (fewer transitive deps, ~80KB JAR, no connection pool to manage), but these gaps need closing.

## What Changes

- **Hybrid upload in `put()`**: Read up to 5MB into a buffer. If the stream fits, use the existing single-part `requestBody(byte[])` PUT. If it exceeds 5MB, switch to the `Multipart` API which streams in 5MB chunks (~5MB peak memory). Metadata is set via `transformCreateRequest()` on the multipart path.
- **Null-check in `getStrippedId()`**: Throw `IllegalArgumentException` when file ID is null.
- **`getSize()` fallback**: When `META_SIZE` is not present in user metadata, fall back to S3 object's native content-length via a HEAD request.

## Capabilities

- **hybrid-upload**: Threshold-based upload strategy — single-part PUT for small files (<5MB), multipart streaming for large files (>=5MB)
- **defensive-checks**: Null-check on file ID, size fallback to content-length

## Impact

- **Performance**: Large file uploads no longer risk OOM. Peak memory per upload capped at ~5MB regardless of file size.
- **Reliability**: Null file IDs fail fast with a clear error instead of NPE deep in S3 client calls.
- **Backwards compatibility**: Fully compatible. Small files behave identically to current code. `META_SIZE` is still written for small files; large files rely on S3 native content-length.
- **No API changes**: `FileStoreService` interface is unchanged.
- **No dependency changes**: `Multipart` is part of the existing `aws-lightweight-client-java` library already on the classpath.
