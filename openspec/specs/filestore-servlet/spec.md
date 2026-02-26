# Filestore Servlet Specification

## Purpose

Provides HTTP servlet endpoints for uploading files to and downloading files from the filestore, with support for multipart file upload with progress tracking, JWT-based token authorization, CORS handling, configurable size limits, and MIME type validation.

## Architecture

- **`UploadServlet`** -- `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` extending `HttpServlet`. Configured via `@Designate(ocd = UploadServlet.Config.class)`. Holds `@Reference` to `HttpService` (mandatory), `FileStoreService` (mandatory, `ReferencePolicyOption.GREEDY`), `TokenValidator` (optional, greedy), and `TokenIssuer` (optional, greedy). Registers itself at the configured `servletPath` via `httpService.registerServlet(...)`. Handles `POST` for multipart upload and `GET` for upload status monitoring, file retrieval, session management, and upload cancellation.
- **`DownloadServlet`** -- `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = Servlet.class)` extending `HttpServlet`. Configured via `@Designate(ocd = DownloadServlet.Config.class)`. Holds `@Reference` to `HttpService` (mandatory), `FileStoreService` (mandatory, greedy), and `TokenValidator` (optional, greedy). Handles `GET` for file downloads with content-disposition, content-type, and content-length headers.
- **`CorsProcessor`** -- Lombok `@Builder` class that processes CORS preflight (`OPTIONS`) and actual requests. Configurable fields: `allowOrigins`, `allowCredentials`, `allowHeaders`, `exposeHeaders`, `maxAge`, `preflightErrorStatus`. The `process(HttpServletRequest, HttpServletResponse, Collection<String>)` method returns `true` if the request should proceed to the servlet, `false` if CORS handling consumed the response.
- **`UploadListener`** -- Extends `AbstractUploadListener`, implements `org.apache.commons.fileupload.ProgressListener`. Stored in the HTTP session under `ATTR_LISTENER = "LISTENER"`. Includes a `TimeoutWatchDog` inner thread that detects frozen uploads by monitoring `bytesRead` progress at 5-second intervals and raises `UploadTimeoutException` after `noDataTimeout` milliseconds of inactivity.
- **`AbstractUploadListener`** -- Abstract base class tracking `bytesRead`, `contentLength`, `exception`, `slowUploads` delay, `frozenTimeout` (60s), and `postResponse`. The `update(long done, long total, int item)` method saves state periodically (every 3 seconds), throws the stored exception if canceled, and optionally sleeps for `slowUploads` milliseconds.
- **`UploadUtils`** -- Static utility class providing `PER_THREAD_REQUEST` ThreadLocal, `copyFromInputStreamToOutputStream(...)`, `findFileItem(...)`, `getSessionFileItems(...)`, `removeSessionFileItems(...)`, `renderJsonResponse(...)`, `renderXmlResponse(...)`, `renderMessage(...)`, `statusToString(...)`, `getContentLength(...)`, `getMessage(String key, Object... pars)` (localized via `ResourceBundle("UploadServlet")`), and `DefaultFileItemFactory` inner class extending `DiskFileItemFactory`.
- **`UploadAction`** -- Abstract subclass of `UploadServlet` adding `executeAction(HttpServletRequest, List<FileItem>)` and `removeItem(...)` extension points for custom post-upload processing.
- **`Constants`** -- Final class defining all constant values: header names (`X-Token`, `Content-Type`, `Content-Disposition`, CORS headers), parameter names (`id`, `show`, `remove`, `cancel`, `clean`, `new_session`, `filename`, `keep_session`), default limits (`DEFAULT_REQUEST_LIMIT_KB = 50 * 1024 * 1024`), MIME types, message templates, and resource bundle keys.
- **Exception classes** -- `UploadSizeLimitException`, `UploadCanceledException`, `UploadTimeoutException`, `UploadException`, `UploadActionException`, `TokenRequiredException`, `MissingParameterException`.

### Dependency Graph

```
UploadServlet ---@Reference---> HttpService
              ---@Reference---> FileStoreService
              ---@Reference(optional)---> TokenValidator
              ---@Reference(optional)---> TokenIssuer
              ----uses----> CorsProcessor
              ----creates----> UploadListener

DownloadServlet ---@Reference---> HttpService
                ---@Reference---> FileStoreService
                ---@Reference(optional)---> TokenValidator
                ----uses----> CorsProcessor
```

## Requirements

### Requirement: Multipart File Upload

The `UploadServlet.doPost(...)` SHALL accept multipart HTTP POST requests parsed via `org.apache.commons.fileupload.servlet.ServletFileUpload`. Each received `FileItem` SHALL be stored using `fileStoreService.put(InputStream, fileName, contentType)`. The response SHALL be JSON with structure `{"files":[...], "finished":"ok"}` where each file entry contains `field`, `id`, `name`, `url`, `ctype`, and `size`. If a `TokenIssuer` is available, each file entry SHALL additionally include a `token` field containing a download token with claims `FILE_ID`, `FILE_NAME`, `FILE_SIZE`, `FILE_MIME_TYPE`, and `CONTEXT`.

#### Scenario: Successful single file upload without token enforcement
- **GIVEN** `UploadServlet` is activated with `tokenRequired = false` and `TokenValidator` is bound
- **WHEN** a multipart POST request is sent containing one file field `avatar` with name `photo.jpg`, content type `image/jpeg`, and size 10240 bytes
- **THEN** the response status is 200, content type is `application/json`, and the body contains `{"files":[{"field":"avatar-0","id":"<uuid>","name":"photo.jpg","url":"<accessUrl>","ctype":"image/jpeg","size":10240,"token":"<jwt>"}],"finished":"ok"}`

#### Scenario: Upload with MIME type validation from upload token
- **GIVEN** `UploadServlet` is activated with `tokenRequired = true`, and the `X-Token` header contains a valid upload token with `UploadClaim.FILE_MIME_TYPE_LIST = "image/png,image/jpeg"`
- **WHEN** a multipart POST is sent with a file of content type `application/pdf`
- **THEN** the file entry in the response JSON contains an `error` field indicating invalid MIME type, and the file is not stored in the filestore

### Requirement: Upload Size Limits

The `UploadServlet` SHALL enforce configurable size limits. `Config.maxSize()` (default `50 * 1024 * 1024` KB) sets the maximum request size. `Config.maxFileSize()` (default `50 * 1024 * 1024` KB) sets the maximum individual file size. The `checkRequest(...)` method SHALL throw `UploadSizeLimitException` if `Content-Length` exceeds `maxSize`. The `ServletFileUpload` instance SHALL have `setSizeMax(...)` and `setFileSizeMax(...)` called with the effective limits. If an upload token contains `UploadClaim.MAX_FILE_SIZE`, that value SHALL override `maxFileSize` for `setFileSizeMax(...)` and SHALL override `maxSize` for `setSizeMax(...)` only if it exceeds the configured `maxSize`.

#### Scenario: Request exceeds maximum size
- **GIVEN** `UploadServlet` is configured with `maxSize = 1048576` (1 MB)
- **WHEN** a POST request is sent with `Content-Length: 2097152` (2 MB)
- **THEN** the response status is 400 (SC_BAD_REQUEST) and the response body contains an error message about size limit exceeded

#### Scenario: Token-based max file size override
- **GIVEN** `UploadServlet` is configured with `maxFileSize = 1048576` and the upload token contains `MAX_FILE_SIZE = 5242880`
- **WHEN** a POST request is sent with a 3 MB file
- **THEN** the upload succeeds because the token's `MAX_FILE_SIZE` overrides the configured `maxFileSize`

### Requirement: Upload Token Enforcement

When `Config.tokenRequired()` is `true`, the `UploadServlet.doPost(...)` SHALL require both `TokenValidator` and `TokenIssuer` to be bound; if either is missing, it SHALL throw `IllegalStateException`. If `TokenValidator` is bound, the servlet SHALL call `tokenValidator.parseUploadToken(request.getHeader("X-Token"))`. If the result is null, a `TokenRequiredException` SHALL be thrown and the response status SHALL be 403 (SC_FORBIDDEN).

#### Scenario: Missing X-Token header when token is required
- **GIVEN** `UploadServlet` is configured with `tokenRequired = true` and `TokenValidator` is bound
- **WHEN** a POST request is sent without the `X-Token` header
- **THEN** the response status is 403 and the body contains an error about missing token

#### Scenario: Token services not ready
- **GIVEN** `UploadServlet` is configured with `tokenRequired = true` but `TokenIssuer` is not bound
- **WHEN** a POST request is sent
- **THEN** the response status is 500 and the body contains an error about the service not being ready

### Requirement: File Download

The `DownloadServlet.doGet(...)` SHALL serve files from the filestore. The file identifier SHALL be resolved from the `id` query parameter or from the `DownloadClaim.FILE_ID` claim in the download token (header `X-Token`). If both are present and differ, an `InvalidTokenException` SHALL be thrown (403). If neither is present, a `MissingParameterException` SHALL be thrown (400). The response SHALL include `Content-Disposition` header (defaulting to `"attachment"`, overridable by the `disposition` query parameter or `DownloadClaim.DISPOSITION` token claim) with the filename, `Content-Type` from the filestore or token fallback, and `Content-Length` if the size fits in an `int`. The file content SHALL be streamed via `UploadUtils.copyFromInputStreamToOutputStream(...)`.

#### Scenario: Download by file ID without token
- **GIVEN** `DownloadServlet` is configured with `tokenRequired = false` and no `TokenValidator` is bound
- **WHEN** a GET request is sent with query parameter `id=abc-123` and the filestore contains a file with ID `abc-123`, name `report.pdf`, type `application/pdf`, size 50000
- **THEN** the response has status 200, `Content-Disposition: attachment; filename="report.pdf"`, `Content-Type: application/pdf`, `Content-Length: 50000`, and the body is the file content

#### Scenario: Download with token and mismatched file ID
- **GIVEN** `DownloadServlet` is configured with `tokenRequired = true`, `TokenValidator` is bound, and the `X-Token` contains `FILE_ID = "abc-123"`
- **WHEN** a GET request is sent with query parameter `id=xyz-789`
- **THEN** the response status is 403 and the body contains an invalid token error

#### Scenario: Inline disposition via query parameter
- **GIVEN** a valid download request with query parameter `disposition=inline`
- **WHEN** the GET request is processed
- **THEN** the `Content-Disposition` header is `inline; filename="<name>"` instead of `attachment`

### Requirement: Download Token Enforcement

When `DownloadServlet.Config.tokenRequired()` is `true`, the servlet SHALL require `TokenValidator` to be bound; if not, it SHALL throw `IllegalStateException`. If `TokenValidator` is bound, the servlet SHALL call `tokenValidator.parseDownloadToken(request.getHeader("X-Token"))`. If the result is null, a `TokenRequiredException` SHALL be thrown with status 403. The token's `FILE_NAME` and `FILE_MIME_TYPE` claims SHALL serve as fallbacks if the filestore does not return a filename or content type for the given file ID.

#### Scenario: Missing X-Token header on download
- **GIVEN** `DownloadServlet` is configured with `tokenRequired = true` and `TokenValidator` is bound
- **WHEN** a GET request is sent without the `X-Token` header
- **THEN** the response status is 403 and the body contains an error about missing token

### Requirement: CORS Processing

Both `UploadServlet` and `DownloadServlet` SHALL delegate CORS processing to `CorsProcessor` before handling requests. The `CorsProcessor.process(...)` method SHALL handle three cases:

1. **OPTIONS preflight**: Validate `Origin` against `allowOrigins`, `Access-Control-Request-Method` against accepted methods, and `Access-Control-Request-Headers` against `allowHeaders` (case-insensitive). On success, respond with 200 and set `Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`, `Access-Control-Max-Age`, and `Access-Control-Allow-Credentials`. On failure, respond with `preflightErrorStatus` (default 400). Return `false` to prevent further servlet processing.
2. **Allowed method**: If `Origin` header is present, validate against `allowOrigins`. If allowed, set `Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials`, and `Access-Control-Expose-Headers`. Return `true`.
3. **Disallowed method**: Respond with 405 (SC_METHOD_NOT_ALLOWED) and return `false`.

The `UploadServlet` SHALL accept methods `GET`, `POST`, `OPTIONS`. The `DownloadServlet` SHALL accept methods `GET`, `OPTIONS`. Both SHALL always include the `X-Token` header in `allowHeaders`. CORS configuration SHALL be sourced from each servlet's `Config`: `cors_allowOrigin` (default `"*"`), `cors_allowCredentials` (default `true`), `cors_allowHeaders`, `cors_exposeHeaders`, `cors_maxAge` (default `-1`), `cors_prefligthErrorStatus` (default `400`).

#### Scenario: Successful CORS preflight for upload
- **GIVEN** `UploadServlet` is configured with `cors_allowOrigin = "https://example.com"`
- **WHEN** an OPTIONS request is sent with `Origin: https://example.com`, `Access-Control-Request-Method: POST`, and `Access-Control-Request-Headers: Content-Type,X-Token`
- **THEN** the response status is 200, `Access-Control-Allow-Origin` is `https://example.com`, `Access-Control-Allow-Methods` is `POST`, `Access-Control-Allow-Headers` includes `Content-Type,X-Token`, and the servlet's `doPost` is not called

#### Scenario: CORS preflight rejected for disallowed origin
- **GIVEN** `UploadServlet` is configured with `cors_allowOrigin = "https://trusted.com"`
- **WHEN** an OPTIONS request is sent with `Origin: https://evil.com`
- **THEN** the response status is 400 (the configured `cors_prefligthErrorStatus`) and no `Access-Control-Allow-Origin` header is set

#### Scenario: Non-preflight request from allowed origin
- **GIVEN** `DownloadServlet` is configured with `cors_allowOrigin = "*"` and `cors_exposeHeaders = "Content-Type,Content-Disposition"`
- **WHEN** a GET request is sent with `Origin: https://any-origin.com`
- **THEN** the response includes `Access-Control-Allow-Origin: https://any-origin.com`, `Access-Control-Allow-Credentials: true`, and `Access-Control-Expose-Headers: Content-Type,Content-Disposition`, and the request proceeds to `doGet`

### Requirement: Upload Progress Monitoring

The `UploadServlet` SHALL support client-side polling for upload progress via GET requests. When a GET request has no recognized query parameter and an `UploadListener` is active in the session, the response SHALL contain `percent`, `currentBytes`, and `totalBytes` as JSON. If the listener has an `UploadCanceledException`, the response SHALL contain `canceled: true`. If the listener has a different exception, the response SHALL contain an `error` field. The `UploadListener` SHALL start a `TimeoutWatchDog` thread that checks for frozen uploads every 5 seconds; if no new bytes are received within `noDataTimeout` milliseconds (default 20000), it SHALL set an `UploadTimeoutException` on the listener.

#### Scenario: Polling upload progress
- **GIVEN** an upload is in progress with 50000 of 100000 bytes received
- **WHEN** a GET request is sent to the upload servlet without query parameters
- **THEN** the response JSON contains `{"percent":"50","currentBytes":"50000","totalBytes":"100000"}`

#### Scenario: Upload timeout detected
- **GIVEN** an upload is in progress and no new data has been received for 20 seconds
- **WHEN** the `TimeoutWatchDog` thread checks the listener
- **THEN** the listener's exception is set to `UploadTimeoutException` with message `"No new data received after 20 seconds"`

### Requirement: Upload Session Management

The `UploadServlet` SHALL support session-based operations via GET query parameters:
- `new_session`: Returns the current session ID as JSON.
- `show=<fieldNameOrFileName>`: Streams the matching uploaded file item from the session.
- `cancel=true`: Sets an `UploadCanceledException` on the active listener.
- `remove=<fieldNameOrFileName>`: Removes the specified file item from the session.
- `clean`: Removes the current listener from the session.

When the upload finishes, if the `keep_session` parameter is not `true`, session file items and the listener SHALL be removed.

#### Scenario: Cancel an in-progress upload
- **GIVEN** an upload is in progress with an active `UploadListener` in the session
- **WHEN** a GET request is sent with query parameter `cancel=true`
- **THEN** the listener's exception is set to `UploadCanceledException`, and the response JSON contains `{"canceled":"true"}`

#### Scenario: Request new session ID
- **GIVEN** a client session exists
- **WHEN** a GET request is sent with query parameter `new_session=true`
- **THEN** the response JSON contains the session ID

### Requirement: Servlet Registration and Deactivation

Both `UploadServlet` and `DownloadServlet` SHALL register themselves with the OSGi `HttpService` at the configured `servletPath` during `@Activate` via `httpService.registerServlet(servletPath, this, initParams, null)`. The `initParams` dictionary SHALL contain `servlet-name` set to `"UploadServlet-<servletPath>"` or `"DownloadServlet-<servletPath>"` respectively. On `@Deactivate`, they SHALL call `httpService.unregister(servletPath)`.

#### Scenario: Servlet registers at configured path
- **GIVEN** `UploadServlet.Config` with `servletPath = "/api/upload"`
- **WHEN** the component is activated
- **THEN** `httpService.registerServlet("/api/upload", this, {"servlet-name": "UploadServlet-/api/upload"}, null)` is called

#### Scenario: Servlet unregisters on deactivation
- **GIVEN** `DownloadServlet` was activated with `servletPath = "/api/download"`
- **WHEN** the component is deactivated
- **THEN** `httpService.unregister("/api/download")` is called
