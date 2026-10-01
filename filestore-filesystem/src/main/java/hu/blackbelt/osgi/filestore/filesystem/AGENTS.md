# DOX — filestore-filesystem/src/main/java/hu/blackbelt/osgi/filestore/filesystem

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `FileSystemFileStoreService.java` | DS component (config REQUIRE) implementing `FileStoreService` on disk: nested 2-char id directories + `.properties` sidecar metadata. Registers a `FileStoreUrlStreamHandler` for its protocol on activate. |
