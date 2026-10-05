# DOX — filestore-s3/src/test/java/hu/blackbelt/osgi/filestore/s3

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `GcsS3FileStoreServiceTest.java` | Opt-in (env/.env) test against a real Google Cloud Storage bucket: small, threshold-sized, multipart and empty files, metadata round-trip. |
| `S3FileStoreServiceMultipartWireTest.java` | No-network test that records HTTP requests and pins multipart wire format (empty initiate body, valid namespace, part sizes/order, abort on failed part). |
| `S3FileStoreServiceTest.java` | MinIO-backed integration tests: round-trips, large/multipart/empty files, connectivity. |
