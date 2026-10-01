# DOX — filestore-s3/src/main/java/hu/blackbelt/osgi/filestore/s3

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `S3FileStoreService.java` | DS component implementing `FileStoreService` on S3-compatible storage (AWS/MinIO/GCS) via the lightweight `aws-lw` client; single PUT below `MULTIPART_THRESHOLD`, multipart upload above; metadata in object headers; registers a `FileStoreUrlStreamHandler`. |
