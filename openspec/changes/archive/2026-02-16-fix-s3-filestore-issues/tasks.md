# Tasks: fix-s3-filestore-issues

All changes in `filestore-s3/src/main/java/hu/blackbelt/osgi/filestore/s3/S3FileStoreService.java`.

## 1. Add constant and imports

- [x] Add `private static final int MULTIPART_THRESHOLD = 5 * 1024 * 1024;` constant
- [x] Add imports: `Multipart`, `ByteArrayInputStream`, `SequenceInputStream`

## 2. Null-check in getStrippedId()

- [x] Add null guard as first line of `getStrippedId()`: throw `IllegalArgumentException(FILE_ID_CANNOT_BE_NULL)` when `id` is null

## 3. Extract putSmallFile() helper

- [x] Extract private method `putSmallFile(String fileId, byte[] content, String fileName, String mimeType, String createTime)` containing the existing single-part PUT logic (lines 135–143): `s3Client.path(...).method(PUT).header(...).metadata(...).requestBody(content).execute()`

## 4. Add putLargeFile() helper

- [x] Add private method `putLargeFile(String fileId, InputStream combinedStream, String fileName, String mimeType, String createTime)` using the Multipart API:
  - `Multipart.s3(s3Client).bucket(bucketName).key(fileId)`
  - `.transformCreateRequest(r -> r.header("Content-Type", mimeType).metadata(META_FILENAME, fn).metadata(META_MIME_TYPE, mt).metadata(META_CREATE_TIME, createTime))`
  - `.upload(() -> combinedStream)`
  - Does NOT store `META_SIZE` (total size unknown during streaming)

## 5. Rewrite put() with buffer-then-decide logic

- [x] Replace `data.readAllBytes()` and direct PUT with:
  1. Allocate `byte[MULTIPART_THRESHOLD]` buffer
  2. Read from `data` in a loop until buffer is full or EOF
  3. If `totalRead < MULTIPART_THRESHOLD`: trim buffer to actual size via `Arrays.copyOf(buffer, totalRead)`, call `putSmallFile()` with `META_SIZE = String.valueOf(trimmedBuffer.length)`
  4. If `totalRead == MULTIPART_THRESHOLD`: probe with one extra `data.read()` call
     - If `-1` (EOF, exactly 5MB): call `putSmallFile()` with full buffer
     - If `>= 0` (more data): construct nested `SequenceInputStream(buffer, SequenceInputStream(extraByte, data))`, call `putLargeFile()`

## 6. Modify getObjectMetadata() to include content-length

- [x] After extracting user metadata from the HEAD response, also read `Content-Length` header via `response.firstHeader("Content-Length").ifPresent(cl -> result.put("__content-length__", cl))`

## 7. Add getSize() fallback

- [x] Modify `getSize()` to:
  1. Get metadata map from `getObjectMetadata()`
  2. Try `metadata.get(META_SIZE)` — if non-null, parse and return
  3. Fall back to `metadata.get("__content-length__")` — if non-null, parse and return
  4. Throw `IllegalArgumentException(NOT_FOUND_MESSAGE)` if neither present

## 8. Update tests

- [x] Add test for small file upload (< 5MB) — verify file stored and retrievable, `getSize()` returns correct value from metadata
- [x] Add test for large file upload (> 5MB) — verify file stored and retrievable, `getSize()` returns correct value via content-length fallback
- [x] Add test for exact 5MB file — verify treated as small file (existing testPutAndGetLargeFile covers this)
- [x] Add test for `getStrippedId()` with null input — verify `IllegalArgumentException` thrown (tightened existing testGetNullFileId)
- [x] Add test for empty file (0 bytes) — verify stored and `getSize()` returns 0
