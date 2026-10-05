# DOX — filestore-urlhandler/src/main/java/hu/blackbelt/osgi/filestore/urlhandler

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `FileStoreUrlConnection.java` | `URLConnection` reading a file's stream, length, content type and date from a `FileStoreService`. |
| `FileStoreUrlStreamHandler.java` | OSGi `URLStreamHandlerService` mapping `protocol:fileId` URLs to `FileStoreUrlConnection`; registered by each backend. |
