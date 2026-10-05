# DOX — filestore-api/src/main/java/hu/blackbelt/osgi/filestore/api

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `FileStoreService.java` | Central backend contract: `put(InputStream,fileName,mimeType)`→fileId, `exists`, `get`, metadata getters (`getMimeType`/`getFileName`/`getSize`/`getCreateTime`), `getAccessUrl`, `getProtocol`. Implemented by filesystem/rdbms/s3 backends. |
| `FilenameUtils.java` | `makeValidFilename` — strips reserved Windows device names and unsafe characters from an uploaded filename. |
