# DOX — filestore-servlet/src/main/java/hu/blackbelt/osgi/filestore/servlet

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `AbstractUploadListener.java` | Commons-FileUpload `ProgressListener` base: tracks bytes read, percent, cancel/finish/frozen state per session. |
| `Constants.java` | String constants for upload XML response tags and parameter names. |
| `DownloadServlet.java` | DS servlet (config REQUIRE): GET by file id with Content-Disposition, CORS handling, optional download-token enforcement. |
| `HasKey.java` | Small contract exposing `getKeyString()`. |
| `UploadAction.java` | `UploadServlet` subclass that stores received multipart items into `FileStoreService` and returns the result (`executeAction`, `removeItem`). |
| `UploadListener.java` | Session-bound `AbstractUploadListener` with a no-data timeout watcher thread. |
| `UploadServlet.java` | DS servlet base: multipart POST parsing, size limits, progress listener, CORS, optional upload-token enforcement, XML status response. |
| `UploadUtils.java` | Static helpers for session file items, stream copy, item lookup by field/file name, thread-local request. |
