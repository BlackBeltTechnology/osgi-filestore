## MODIFIED Requirements

### Requirement: CORS Processing

Both `UploadServlet` and `DownloadServlet` SHALL delegate CORS processing to `CorsProcessor` before handling requests. The `CorsProcessor.process(...)` method SHALL handle three cases:

1. **OPTIONS preflight**: Validate `Origin` against `allowOrigins`, `Access-Control-Request-Method` against accepted methods, and `Access-Control-Request-Headers` against `allowHeaders` (case-insensitive). On success, respond with 200 and set `Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers` and `Access-Control-Max-Age`, plus `Access-Control-Allow-Credentials` subject to the wildcard rule below. On failure, respond with `preflightErrorStatus` (default 400). Return `false` to prevent further servlet processing.
2. **Allowed method**: If an `Origin` header is present, validate it against `allowOrigins`. If allowed, set `Access-Control-Allow-Origin` and `Access-Control-Expose-Headers`, plus `Access-Control-Allow-Credentials` subject to the wildcard rule below. Return `true`.
3. **Disallowed method**: Respond with 405 (SC_METHOD_NOT_ALLOWED) and return `false`.

**Wildcard rule:** when `allowOrigins` contains `*`, the processor SHALL set `Access-Control-Allow-Origin` to the literal value `*`, SHALL NOT echo the request's `Origin`, and SHALL NOT set `Access-Control-Allow-Credentials`, regardless of the `allowCredentials` setting. When `allowOrigins` is an explicit list, the processor SHALL echo the matching request `Origin` and SHALL set `Access-Control-Allow-Credentials` to the configured `allowCredentials` value.

The `UploadServlet` SHALL accept methods `GET`, `POST`, `OPTIONS`. The `DownloadServlet` SHALL accept methods `GET`, `OPTIONS`. Both SHALL always include the `X-Token` header in `allowHeaders`. CORS configuration SHALL be sourced from each servlet's `Config`: `cors_allowOrigin` (default `"*"`), `cors_allowCredentials` (default `true`), `cors_allowHeaders`, `cors_exposeHeaders`, `cors_maxAge` (default `-1`), `cors_prefligthErrorStatus` (default `400`).

#### Scenario: Successful CORS preflight for upload
- **GIVEN** `UploadServlet` is configured with `cors_allowOrigin = "https://example.com"`
- **WHEN** an OPTIONS request is sent with `Origin: https://example.com`, `Access-Control-Request-Method: POST`, and `Access-Control-Request-Headers: Content-Type,X-Token`
- **THEN** the response status is 200, `Access-Control-Allow-Origin` is `https://example.com`, `Access-Control-Allow-Methods` is `POST`, `Access-Control-Allow-Headers` includes `Content-Type,X-Token`, `Access-Control-Allow-Credentials` is `true`, and the servlet's `doPost` is not called

#### Scenario: CORS preflight rejected for disallowed origin
- **GIVEN** `UploadServlet` is configured with `cors_allowOrigin = "https://trusted.com"`
- **WHEN** an OPTIONS request is sent with `Origin: https://evil.com`
- **THEN** the response status is 400 (the configured `cors_prefligthErrorStatus`) and no `Access-Control-Allow-Origin` header is set

#### Scenario: Non-preflight request from allowed origin
- **GIVEN** `DownloadServlet` is configured with `cors_allowOrigin = "*"`, `cors_allowCredentials = true` and `cors_exposeHeaders = "Content-Type,Content-Disposition"`
- **WHEN** a GET request is sent with `Origin: https://any-origin.com`
- **THEN** the response includes `Access-Control-Allow-Origin: *` and `Access-Control-Expose-Headers: Content-Type,Content-Disposition`
- **AND** the response does not include `Access-Control-Allow-Credentials`
- **AND** the request proceeds to `doGet`

#### Scenario: Wildcard preflight does not grant credentials
- **GIVEN** `UploadServlet` is configured with `cors_allowOrigin = "*"` and `cors_allowCredentials = true`
- **WHEN** an OPTIONS request is sent with `Origin: https://evil.com` and `Access-Control-Request-Method: POST`
- **THEN** the response status is 200 and `Access-Control-Allow-Origin` is `*`
- **AND** the response does not include `Access-Control-Allow-Credentials`

#### Scenario: Explicit origin list keeps credentials
- **GIVEN** `DownloadServlet` is configured with `cors_allowOrigin = "https://app.example.com"` and `cors_allowCredentials = true`
- **WHEN** a GET request is sent with `Origin: https://app.example.com`
- **THEN** the response includes `Access-Control-Allow-Origin: https://app.example.com` and `Access-Control-Allow-Credentials: true`

#### Scenario: Request without Origin header
- **GIVEN** either servlet with any CORS configuration
- **WHEN** a request for an accepted method is sent without an `Origin` header
- **THEN** no CORS response headers are set and the request proceeds to the servlet

## ADDED Requirements

### Requirement: Unsafe Servlet Configuration Warnings

On activation, `UploadServlet` and `DownloadServlet` SHALL log at WARN level, once per activation, for each unsafe effective configuration. A warning SHALL NOT change request handling.

- `tokenRequired = false`: the warning SHALL state that requests are not authenticated when no `TokenValidator` is bound.
- `cors_allowOrigin` contains `*` while `cors_allowCredentials = true`: the warning SHALL state that credentials are not granted to wildcard origins and that an explicit origin list is required for credentialed requests.

#### Scenario: Token enforcement disabled
- **GIVEN** a servlet `Config` with `tokenRequired = false`
- **WHEN** the servlet is activated
- **THEN** one WARN entry is logged naming the servlet path and `tokenRequired`

#### Scenario: Wildcard origin with credentials
- **GIVEN** a servlet `Config` with `cors_allowOrigin = "*"` and `cors_allowCredentials = true`
- **WHEN** the servlet is activated
- **THEN** one WARN entry is logged naming `cors.allowOrigin` and `cors.allowCredentials`

#### Scenario: Safe configuration is silent
- **GIVEN** a servlet `Config` with `tokenRequired = true` and `cors_allowOrigin = "https://app.example.com"`
- **WHEN** the servlet is activated
- **THEN** no WARN entry about unsafe configuration is logged
