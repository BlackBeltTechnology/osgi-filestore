## Why

Two configuration defaults in the filestore fail open. When an install does not set them, which is the normal case for judo-platform, the fail-open value applies. In judo-platform it comes from the dispatcher config template (`filestoreTokenExpiry!"0"`, `corsAllowOrigin!"*"`, `corsAllowCredentials!"true"`), which mirrors the component defaults here:

1. **Tokens never expire** (`expirationTime = 0`). A leaked upload result or download token stays valid forever.
2. **CORS accepts any site with credentials.** With the default `cors.allowOrigin = "*"` and `cors.allowCredentials = true`, `CorsProcessor` does not send a literal `*`. It echoes the caller's `Origin` back together with `Access-Control-Allow-Credentials: true`, which browsers treat as "this specific site may make credentialed requests".

Other unsafe settings work as configured but give no signal: `tokenRequired = false`, which leaves the endpoints open when no `TokenValidator` is bound, and an `http://` S3 endpoint. Operators cannot see these states today.

judo-platform already forces `tokenRequired = true`, so token enforcement is not in scope. See `filestore-s3/SECURITY.md` finding S-4 for the correction.

## What Changes

- **BREAKING (default only):** `TokenServiceConfig.expirationTime` defaults to **1440 minutes (24 h)** instead of `0`. An explicit `0` still means "never expires". Tokens with no `exp` claim, issued before the upgrade, are rejected once the validator runs with a non-zero expiry.
- New `TokenServiceConfig.allowedClockSkew` setting in seconds, **default 60**, applied by the validator so tokens near expiry do not fail at random between nodes with slightly different clocks.
- **BREAKING (wildcard CORS only):** when the configured origin list contains `*`, both servlets answer with a literal `Access-Control-Allow-Origin: *` and **no** `Access-Control-Allow-Credentials` header, whatever `cors.allowCredentials` is set to. Configurations with an explicit origin list behave exactly as before.
- New WARN log at activation for each unsafe effective configuration:
  - a servlet with `tokenRequired = false`
  - a servlet whose origin list is `*` while `cors.allowCredentials = true` (credentials will not be granted)
  - a token issuer with `expirationTime = 0`
  - an S3 store with an `http://` endpoint
- No Java API, PID, or metatype attribute is removed or renamed. Minor version bump.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `filestore-servlet`: the "CORS Processing" requirement changes for wildcard origins. A new requirement adds activation warnings for unsafe servlet configuration.
- `filestore-security`: token expiration gets a non-zero default and a clock-skew tolerance. A new requirement adds a warning for non-expiring tokens.
- `filestore-s3`: a new requirement adds a warning for cleartext (`http://`) endpoints.

## Impact

- **Code:** `filestore-servlet` (`CorsProcessor`, the `UploadServlet` and `DownloadServlet` config defaults and activation), `filestore-security` (`TokenServiceConfig`, `DefaultTokenIssuer`, `DefaultTokenValidator`), `filestore-s3` (`S3FileStoreService.activate`).
- **judo-platform / judo-runtime-core:**
  - **judo-platform config template:** the dispatcher template default changes from `${filestoreTokenExpiry!"0"}` to `${filestoreTokenExpiry!"1440"}`, otherwise the new expiry default never reaches platform apps (the template always passes an explicit value). Operators override it with `JUDO_PLATFORM_FILESTORE_TOKEN_EXPIRY` (`0` = never expires). No new environment variable is introduced. CORS is controlled by the existing `JUDO_PLATFORM_CORS_ALLOW_ORIGIN` / `JUDO_PLATFORM_CORS_ALLOW_CREDENTIALS`.
  - `RequestConverter` validates the download token on every save of an entity with a binary attribute. `ResponseConverter` issues a new token on every read.
  - **Downstream repositories that need a release-time version bump of `osgi-filestore-version`, in dependency order:** `judo-runtime-core` (`pom.xml:77`) and `judo-platform` (`pom.xml:46`). `judo-runtime-core` needs **no code change** — it depends on `filestore-security-api` interfaces only (`JudoDefaultSpringConfiguration`, guice `DefaultDispatcherProvider`, `RequestConverter`, `ResponseConverter`, `GetUploadTokenCall`, `DefaultDispatcher`) and never configures expiry or CORS; it only owns the `ERROR_INVALID_FILE_TOKEN` path a user hits when a token expires. `judo-platform` additionally carries the config-template change. `judo-ng/runtime/judo-services` also references filestore but is a **superseded copy merged into judo-platform** (pinned at a 2024 filestore build, absent from `project-meta.yml`'s release list) and is out of scope.
  - The token travels only between server and browser. The database stores `FileType` JSON (`id`, `fileName`, `size`, `mimeType`) and no token, so **no data migration** is needed.
  - Visible effect: a form or tab kept open for more than 24 h fails to save with `ERROR_INVALID_FILE_TOKEN`, and the user has to reload.
- **Browser clients:** only cross-origin credentialed requests (`credentials: 'include'` / `withCredentials`) against a wildcard-configured servlet stop working. **None exist in the generated UI** (checked 2026-10-05): the React client generator is `judo-tatami-typescript`, and its filestore calls in `data-axios/src/AccessServiceImpl.ts` authenticate with an `X-Token` **header**, never with cookies:
  - `uploadFile` requests an upload token, then `POST`s the `FormData` with `Content-Type: multipart/form-data` and `X-Token: <token>`.
  - `downloadFile` issues a `GET` with `responseType: 'blob'` and `X-Token: <downloadToken>`.
  - The shared axios instance (`main.tsx.hbs` → `judoAxiosProvider.init`) sets no `withCredentials`, and application auth is an OIDC **Bearer** header added by `axiosRequestInterceptor` from `sessionStorage` — again not a cookie.
  - A `withCredentials` / `credentials: 'include'` search over `judo-tatami-typescript`, `judo-ui-e2e-template`, `judo-ui-page-object-template`, `judo-community` and `judo-ng` returned no hits outside `node_modules` / `target`.
  Because the filestore base URL (`FILE_DEFAULT_BASE_URL`) may differ from the API origin, those calls can genuinely be cross-origin — and the `X-Token` header makes them preflighted — but they carry no credentials, so the wildcard rule does not affect them. Both servlets always add `X-Token` to `allowHeaders`, so preflight keeps succeeding. Same-origin and server-to-server calls are unaffected.
- **Rollback:** configuration only, by setting `expirationTime=0` or an explicit `cors.allowOrigin` list. Nothing is persisted or migrated.
- **Out of scope:**
  - the per-node generated signing secret (S-6)
  - the judo-platform `DispatcherServiceActivator` wiring and documentation of `FILESTORE_TOKEN_EXPIRY` / `filestore.cors.*`
  - separate expiry settings for upload results and download links
