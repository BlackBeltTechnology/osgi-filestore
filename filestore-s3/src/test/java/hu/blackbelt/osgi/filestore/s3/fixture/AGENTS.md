# DOX — filestore-s3/src/test/java/hu/blackbelt/osgi/filestore/s3/fixture

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `DotEnv.java` | Minimal `.env` loader for opt-in test credentials. |
| `GcsFixture.java` | GCS test fixture: configuration check, bucket reachability, client, object cleanup. |
| `MinioFixture.java` | TestContainers `MinIOContainer` setup/teardown, on `cgr.dev/chainguard/minio:latest` (`minio/minio` is no longer pullable from Docker Hub). |
| `MinioSingletonExtension.java` | JUnit 5 extension sharing one MinIO fixture + parameter injection. |
